package io.mosire.iibot.taskflow.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.decision.ResolvedCall;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.PlayerAction;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 裁定包领域服务。
 *
 * <p>包状态机完全交给 AgentLib {@link DecisionPackages}；本类只负责：宿主 packageId、把行动池
 * 正文转换为 {@code player_action} 包项、在包状态成功后同步行动池的 SELECTED/COMMITTED
 * 状态，以及把批准子集交给宿主 {@link ActionBatchExecutor}。</p>
 *
 * <p>本类不打印条目正文；PackageView 默认不携带 args。只有显式 {@code get(..., true)} 才
 * 返回明文，读取审计由 app 负责。</p>
 */
public final class ActionPackageService {

    public static final String TOOL_NAME = "player_action";

    private static final String CREATE_KEY_SEPARATOR = "\u001F";
    private static final Set<ActorRole> GM_ROLES =
            Set.of(ActorRole.DIRECTOR, ActorRole.GM, ActorRole.SUPERVISOR);

    private final ActionPoolService actions;
    private final DecisionPackages packages;
    private final Clock clock;
    private final Map<String, String> titles = new LinkedHashMap<>();
    private final Map<String, String> createIdempotency = new LinkedHashMap<>();
    private final List<ActionBatch> batches = new ArrayList<>();

    public ActionPackageService(ActionPoolService actions, DecisionPackages packages,
                                Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 建包。只接受 DIRECTOR/GM/SUPERVISOR；所有 actionIds 必须存在、去重且均为 QUEUED。
     * 成功后这些行动由行动池标为 SELECTED 并绑定 packageId。
     */
    public synchronized PackageView create(Actor gm, String title, List<String> actionIds,
                                           long deadlineSeconds, String idempotencyKey) {
        requireGm(gm);
        requireText(title, "title");
        requireIds(actionIds, "actionIds");
        requireText(idempotencyKey, "idempotencyKey");
        if (deadlineSeconds <= 0) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    "deadlineSeconds 必须为正");
        }

        String createKey = gm.actorId() + CREATE_KEY_SEPARATOR + idempotencyKey;
        String existingId = createIdempotency.get(createKey);
        if (existingId != null) {
            return summaryView(packages.find(existingId).orElseThrow(() ->
                    new ActionPackageException(ActionPackageException.NOT_FOUND,
                            "幂等键指向的裁定包不存在: " + existingId)));
        }

        List<PlayerAction> selected = new ArrayList<>(actionIds.size());
        for (String actionId : actionIds) {
            PlayerAction action = requireQueued(actionId);
            selected.add(action);
        }

        long deadlineEpochMs;
        try {
            // DecisionPackages 的内存适配器用系统墙钟判定到点；取两者较晚者，避免宿主
            // 注入的 Clock 落后于系统时间时新建包立刻被标成 EXPIRED。
            long baseMillis = Math.max(clock.millis(), System.currentTimeMillis());
            deadlineEpochMs = Math.addExact(baseMillis,
                    Math.multiplyExact(deadlineSeconds, 1000L));
        } catch (ArithmeticException overflow) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    "deadlineSeconds 过大");
        }
        if (deadlineEpochMs <= 0) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    "计算后的 deadlineEpochMs 必须为正");
        }

        String packageId = "pkg-" + UUID.randomUUID();
        List<PackageItem> items = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            items.add(toItem(i, title, selected.get(i)));
        }
        ProposeRequest request = new ProposeRequest("gm-package:" + gm.actorId(),
                AccessToken.DEFAULT, items, deadlineEpochMs);
        DecisionPackage created = packages.propose(packageId, request);
        if (created.state() != PackageState.PROPOSED) {
            throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                    "建包未进入 PROPOSED 状态: " + created.state());
        }

        for (PlayerAction action : selected) {
            actions.markSelected(action.actionId(), packageId);
        }
        titles.put(packageId, title);
        createIdempotency.put(createKey, packageId);
        return summaryView(created);
    }

    /** 仅在 PROPOSED 且尚无逐条表态时追加；先由 AgentLib 完成状态检查，失败不触碰行动池。 */
    public synchronized PackageView append(Actor gm, String packageId, List<String> actionIds) {
        requireGm(gm);
        requireText(packageId, "packageId");
        requireIds(actionIds, "actionIds");
        DecisionPackage current = requirePackage(packageId);
        List<PlayerAction> selected = new ArrayList<>(actionIds.size());
        for (String actionId : actionIds) {
            selected.add(requireQueued(actionId));
        }
        int nextIndex = current.items().stream().mapToInt(PackageItem::index).max().orElse(-1) + 1;
        List<PackageItem> items = new ArrayList<>(selected.size());
        String title = titleFor(packageId);
        for (int i = 0; i < selected.size(); i++) {
            items.add(toItem(nextIndex + i, title, selected.get(i)));
        }
        DecisionPackage appended = packages.append(packageId, items);
        for (PlayerAction action : selected) {
            actions.markSelected(action.actionId(), packageId);
        }
        return summaryView(appended);
    }

    /** 仅在 PROPOSED 且尚无逐条表态时原位替换；AgentLib 拒绝时旧/新行动状态均不变。 */
    public synchronized PackageView replaceItem(Actor gm, String packageId, int index,
                                                String actionId) {
        requireGm(gm);
        requireText(packageId, "packageId");
        requireText(actionId, "actionId");
        DecisionPackage current = requirePackage(packageId);
        PackageItem oldItem = findItem(current, index);
        PlayerAction oldAction = requirePackageAction(oldItem, packageId);
        PlayerAction replacementAction = requireQueued(actionId);
        if (oldAction.actionId().equals(replacementAction.actionId())) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    "替换行动不能与旧行动相同");
        }
        PackageItem replacement = toItem(index, titleFor(packageId), replacementAction);
        DecisionPackage replaced = packages.replaceItem(packageId, index, replacement);
        actions.markSelected(replacementAction.actionId(), packageId);
        if (oldAction.status() == ActionStatus.SELECTED) {
            actions.releaseSelected(oldAction.actionId());
        }
        return summaryView(replaced);
    }

    /** 仅在 PROPOSED 且尚无逐条表态时删除；删空包由 AgentLib 拒绝且不触碰行动池。 */
    public synchronized PackageView removeItem(Actor gm, String packageId, int index) {
        requireGm(gm);
        requireText(packageId, "packageId");
        DecisionPackage current = requirePackage(packageId);
        PackageItem oldItem = findItem(current, index);
        PlayerAction oldAction = requirePackageAction(oldItem, packageId);
        DecisionPackage removed = packages.removeItem(packageId, index);
        if (oldAction.status() == ActionStatus.SELECTED) {
            actions.releaseSelected(oldAction.actionId());
        }
        return summaryView(removed);
    }

    /** 包摘要列表；永远不携带 args 正文。 */
    public synchronized List<PackageView> list() {
        return packages.all().stream().map(this::summaryView).toList();
    }

    /** 只读包摘要快照；供 app 后续 JSON 序列化/恢复。 */
    public synchronized List<PackageView> snapshot() {
        return list();
    }

    /** 包视图；full=false 时条目 args 为 null。 */
    public synchronized Optional<PackageView> get(String packageId, boolean full) {
        requireText(packageId, "packageId");
        return packages.find(packageId).map(pkg -> view(pkg, full));
    }

    /** 整包裁决；仅 GM 侧角色，且 AgentLib 只接受 PROPOSED。 */
    public synchronized PackageView decide(Actor gm, String packageId,
                                           Set<Integer> approvedIndexes, String note) {
        requireGm(gm);
        requireText(packageId, "packageId");
        Set<Integer> approved = approvedIndexes == null ? Set.of() : Set.copyOf(approvedIndexes);
        DecisionPackage decided = packages.decide(packageId,
                new Decision(approved, gm.actorId(), note == null ? "" : note));
        return summaryView(decided);
    }

    /** 逐条裁决；首次表态后包进入 DECIDING，内容编辑由 AgentLib 冻结。 */
    public synchronized PackageView decideItem(Actor gm, String packageId, int index,
                                               boolean approve, String note) {
        requireGm(gm);
        requireText(packageId, "packageId");
        DecisionPackages.ItemDecisionResult result = packages.itemDecided(packageId, index,
                approve, gm.actorId(), note == null ? "" : note);
        return summaryView(result.pkg());
    }

    /**
     * 提交已批子集并形成行动批次。依赖 AgentLib 保证同一个包只执行一次；第二次调用返回
     * 首次回执且不会再次调用执行器。未批准条目在成功提交后释放回 QUEUED（明确保留，未执行）。
     */
    public synchronized CommitReceipt commit(Actor gm, String packageId,
                                             ActionBatchExecutor executor) {
        requireGm(gm);
        requireText(packageId, "packageId");
        Objects.requireNonNull(executor, "executor");
        requirePackage(packageId);
        DecisionCommitPort port = (id, calls) -> executeApproved(id, calls, executor);
        CommitReceipt receipt = packages.commit(packageId, port);
        reconcileActionStates(packageId, receipt);
        return receipt;
    }

    /** 已产生的批次只读列表（按提交顺序）。 */
    public synchronized List<ActionBatch> batches() {
        return List.copyOf(batches);
    }

    private CommitReceipt executeApproved(String packageId, List<ResolvedCall> calls,
                                          ActionBatchExecutor executor) {
        List<PlayerAction> resolved = new ArrayList<>(calls.size());
        for (ResolvedCall call : calls) {
            if (!TOOL_NAME.equals(call.toolName())) {
                return rejected(calls, "包内条目工具名不是 " + TOOL_NAME);
            }
            Optional<PlayerAction> found = actions.getForGm(requireArg(call.args(), "actionId"));
            if (found.isEmpty()) {
                return rejected(calls, "包内行动已不存在");
            }
            PlayerAction action = found.get();
            if (action.status() != ActionStatus.SELECTED
                    || !packageId.equals(action.packageId())) {
                return rejected(calls, "包内行动不在本包的 SELECTED 状态");
            }
            if (!action.playerId().equals(requireArg(call.args(), "playerId"))
                    || !action.text().equals(requireArg(call.args(), "text"))) {
                return rejected(calls, "包内行动参数与行动池不一致");
            }
            resolved.add(action);
        }

        ActionBatch pending = new ActionBatch("batch-" + UUID.randomUUID(), packageId, null,
                resolved.stream().map(PlayerAction::actionId).toList(),
                resolved.stream().map(PlayerAction::text).toList(),
                resolved.stream().map(PlayerAction::playerId).toList(),
                clock.instant(), ActionBatchStatus.PENDING, null);
        ActionBatch finished;
        try {
            finished = executor.execute(pending);
        } catch (RuntimeException failure) {
            return rejected(calls, "行动批次执行器失败，未标记为已提交");
        }
        if (finished == null) {
            return rejected(calls, "行动批次执行器返回 null");
        }
        if (finished.status() != ActionBatchStatus.COMMITTED) {
            batches.add(finished);
            CommitReceipt.Status status = finished.status() == ActionBatchStatus.CONFLICTED
                    ? CommitReceipt.Status.CONFLICTED : CommitReceipt.Status.REJECTED;
            return new CommitReceipt(status, null, notCommitted(calls.size()),
                    "行动批次未以 COMMITTED 结束: " + finished.status());
        }

        for (PlayerAction action : resolved) {
            actions.markCommitted(action.actionId());
        }
        batches.add(finished);
        return new CommitReceipt(CommitReceipt.Status.COMMITTED, finished.batchId(),
                Collections.nCopies(calls.size(), CommitReceipt.ItemOutcome.COMMITTED),
                finished.result() == null ? "行动批次已提交" : finished.result());
    }

    private void reconcileActionStates(String packageId, CommitReceipt receipt) {
        Optional<DecisionPackage> found = packages.find(packageId);
        if (found.isEmpty()) {
            return;
        }
        DecisionPackage pkg = found.get();
        Set<Integer> approved = pkg.decision() == null
                ? Set.of() : pkg.decision().approvedIndexes();
        boolean committed = receipt.status() == CommitReceipt.Status.COMMITTED;
        for (PackageItem item : pkg.items()) {
            String actionId = actionId(item);
            if (actionId == null) {
                continue;
            }
            Optional<PlayerAction> action = actions.getForGm(actionId);
            if (action.isEmpty()) {
                continue;
            }
            PlayerAction current = action.get();
            if (committed && approved.contains(item.index())) {
                if (current.status() == ActionStatus.SELECTED) {
                    actions.markCommitted(actionId);
                }
            } else if (current.status() == ActionStatus.SELECTED
                    && packageId.equals(current.packageId())) {
                actions.releaseSelected(actionId);
            }
        }
    }

    private CommitReceipt rejected(List<ResolvedCall> calls, String reason) {
        return new CommitReceipt(CommitReceipt.Status.REJECTED, null,
                notCommitted(calls.size()), reason);
    }

    private static List<CommitReceipt.ItemOutcome> notCommitted(int count) {
        return Collections.nCopies(count, CommitReceipt.ItemOutcome.NOT_COMMITTED);
    }

    private PlayerAction requireQueued(String actionId) {
        PlayerAction action = actions.getForGm(actionId).orElseThrow(() ->
                new ActionPackageException(ActionPackageException.NOT_FOUND,
                        "行动不存在: " + actionId));
        if (action.status() != ActionStatus.QUEUED) {
            throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                    "行动不在 QUEUED 状态: " + actionId + " status=" + action.status());
        }
        return action;
    }

    private DecisionPackage requirePackage(String packageId) {
        return packages.find(packageId).orElseThrow(() ->
                new ActionPackageException(ActionPackageException.NOT_FOUND,
                        "裁定包不存在: " + packageId));
    }

    private static PackageItem findItem(DecisionPackage pkg, int index) {
        return pkg.items().stream().filter(item -> item.index() == index).findFirst()
                .orElseThrow(() -> new ActionPackageException(
                        ActionPackageException.INVALID_ARGUMENTS,
                        "包内不存在 index=" + index));
    }

    private PlayerAction requirePackageAction(PackageItem item, String packageId) {
        String actionId = actionId(item);
        if (actionId == null) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    "包条目缺少 actionId");
        }
        PlayerAction action = actions.getForGm(actionId).orElseThrow(() ->
                new ActionPackageException(ActionPackageException.NOT_FOUND,
                        "包内行动不存在: " + actionId));
        if (action.status() != ActionStatus.SELECTED
                || !packageId.equals(action.packageId())) {
            throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                    "包内行动未绑定到本包: " + actionId);
        }
        return action;
    }

    private PackageItem toItem(int index, String title, PlayerAction action) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("actionId", action.actionId());
        args.put("playerId", action.playerId());
        args.put("text", action.text());
        args.put("source", action.source());
        args.put("submittedAt", action.createdAt().toEpochMilli());
        Map<String, Object> frozenArgs = Map.copyOf(args);
        String digest = DecisionItemDigest.argsDigest(frozenArgs);
        String summary = title + " / #" + index + " player=" + action.playerId()
                + " action=" + action.actionId();
        return new PackageItem(index, TOOL_NAME, frozenArgs, digest, summary);
    }

    private PackageView summaryView(DecisionPackage pkg) {
        return view(pkg, false);
    }

    private PackageView view(DecisionPackage pkg, boolean full) {
        List<PackageItemView> itemViews = new ArrayList<>(pkg.items().size());
        for (PackageItem item : pkg.items()) {
            itemViews.add(new PackageItemView(item.index(), item.toolName(), item.argsDigest(),
                    item.summary(), full ? item.args() : null));
        }
        return new PackageView(pkg.id(), titleFor(pkg.id()), pkg.proposerKey(), pkg.state(),
                pkg.createdAtEpochMs(), pkg.deadlineEpochMs(), itemViews, pkg.decision());
    }

    private String titleFor(String packageId) {
        return titles.getOrDefault(packageId, "");
    }

    private static String actionId(PackageItem item) {
        Object value = item.args().get("actionId");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String requireArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("包条目缺少字符串参数 " + key);
        }
        return text;
    }

    private static void requireGm(Actor actor) {
        if (actor == null || !GM_ROLES.contains(actor.role())) {
            throw new ActionPackageException(ActionPackageException.FORBIDDEN,
                    "只有 DIRECTOR/GM/SUPERVISOR 可以管理裁定包");
        }
    }

    private static void requireIds(List<String> actionIds, String name) {
        if (actionIds == null || actionIds.isEmpty()) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    name + " 不能为空");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String actionId : actionIds) {
            requireText(actionId, name + " 含空值");
            if (!unique.add(actionId)) {
                throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                        name + " 不能包含重复 actionId");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    name + " 不能为空白");
        }
    }
}
