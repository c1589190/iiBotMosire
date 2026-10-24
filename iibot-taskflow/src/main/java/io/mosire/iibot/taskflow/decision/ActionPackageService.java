package io.mosire.iibot.taskflow.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
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
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>包状态机完全交给 AgentLib {@link DecisionPackages}；本类负责宿主 packageId、把行动池
 * 正文转换为 {@code player_action} 包项，以及把批准子集交给宿主
 * {@link ActionBatchExecutor}。</p>
 *
 * <p><b>单状态 owner</b>：PACKAGE_COMMAND_ 与 BATCH_COMPLETED 事件与包/批次内存状态是
 * canonical 事实；ActionPool 的 SELECTED/COMMITTED 只是投影。投影同步失败只记录
 * pending reconcile，不得改写包/批次成功结果，也不得补写 FAILED 命令。</p>
 *
 * <p>本类不打印条目正文；PackageView 默认不携带 args。只有显式 {@code get(..., true)} 才
 * 返回明文，读取审计由 app 负责。</p>
 */
public final class ActionPackageService {

    public static final String TOOL_NAME = "player_action";
    public static final String EVENT_JOURNAL_UNAVAILABLE = "EVENT_JOURNAL_UNAVAILABLE";

    private static final Logger LOG = LoggerFactory.getLogger(ActionPackageService.class);
    private static final String CREATE_KEY_SEPARATOR = "\u001F";
    private static final String PACKAGE_ACTOR = "system:action-package";
    private static final long RECOVERY_DEADLINE_FLOOR_MILLIS = 60L * 60L * 1000L;
    private static final int MAX_RECOVERY_EVENTS = 10_000;
    private static final Set<ActorRole> GM_ROLES =
            Set.of(ActorRole.DIRECTOR, ActorRole.GM, ActorRole.SUPERVISOR);

    private final ActionPoolService actions;
    private final DecisionPackages packages;
    private final Clock clock;
    private final DomainEventJournal journal;
    private boolean journalFailed;
    private boolean actionReconcilePending;
    private final Map<String, String> titles = new LinkedHashMap<>();
    private final Map<String, String> createIdempotency = new LinkedHashMap<>();
    private final List<ActionBatch> batches = new ArrayList<>();
    private final Map<String, CommitReceipt> normalizedReceipts = new LinkedHashMap<>();
    private final Set<String> batchCompletedWritten = new LinkedHashSet<>();

    /** 旧构造器：不写包命令事件，保持 P5-A 以前行为。 */
    public ActionPackageService(ActionPoolService actions, DecisionPackages packages,
                                Clock clock) {
        this(actions, packages, clock, null);
    }

    /** 生产构造器：写操作追加 PACKAGE_COMMAND_* 与 BATCH_COMPLETED 事件。 */
    public ActionPackageService(ActionPoolService actions, DecisionPackages packages,
                                Clock clock, DomainEventJournal journal) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.journal = journal;
    }

    public ActionPackageService(ActionPoolService actions, DecisionPackages packages,
                                DomainEventJournal journal, Clock clock) {
        this(actions, packages, clock, journal);
    }

    /** 事件 journal 进入 fail-stop 后为 true；为 true 时所有正式写口拒绝继续。 */
    public synchronized boolean journalFailed() {
        return journalFailed;
    }

    /** ActionPool 投影是否仍有待修复的偏差；package/batch 事件仍是主事实。 */
    public synchronized boolean actionReconcilePending() {
        return actionReconcilePending;
    }

    /**
     * 建包。只接受 DIRECTOR/GM/SUPERVISOR；所有 actionIds 必须存在、去重且均为 QUEUED。
     * 成功后这些行动由行动池标为 SELECTED 并绑定 packageId。
     */
    public synchronized PackageView create(Actor gm, String title, List<String> actionIds,
                                           long deadlineSeconds, String idempotencyKey) {
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
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

        requireCanonicalAvailable(actionIds);
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
        String proposerKey = "gm-package:" + gm.actorId();
        ProposeRequest request = new ProposeRequest(proposerKey,
                AccessToken.DEFAULT, items, deadlineEpochMs);
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.CREATE,
                packageId, gm.actorId(), gm.role(), title, proposerKey, items,
                null, null, null, "", deadlineEpochMs, idempotencyKey, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionPackage created = packages.propose(packageId, request);
            if (created.state() != PackageState.PROPOSED) {
                throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                        "建包未进入 PROPOSED 状态: " + created.state());
            }
            for (PlayerAction action : selected) {
                syncProjectionBestEffort(
                        () -> actions.markSelected(action.actionId(), packageId));
            }
            titles.put(packageId, title);
            createIdempotency.put(createKey, packageId);
            markApplied(trace, command);
            return summaryView(created);
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
    }

    /** 仅在 PROPOSED 且尚无逐条表态时追加；先由 AgentLib 完成状态检查，失败不触碰行动池。 */
    public synchronized PackageView append(Actor gm, String packageId, List<String> actionIds) {
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
        requireGm(gm);
        requireText(packageId, "packageId");
        requireIds(actionIds, "actionIds");
        requireCanonicalAvailable(actionIds);
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
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.APPEND,
                packageId, gm.actorId(), gm.role(), title, current.proposerKey(), items,
                null, null, null, "", current.deadlineEpochMs(), null, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionPackage appended = packages.append(packageId, items);
            for (PlayerAction action : selected) {
                syncProjectionBestEffort(
                        () -> actions.markSelected(action.actionId(), packageId));
            }
            markApplied(trace, command);
            return summaryView(appended);
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
    }

    /** 仅在 PROPOSED 且尚无逐条表态时原位替换；AgentLib 拒绝时旧/新行动状态均不变。 */
    public synchronized PackageView replaceItem(Actor gm, String packageId, int index,
                                                String actionId) {
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
        requireGm(gm);
        requireText(packageId, "packageId");
        requireText(actionId, "actionId");
        requireCanonicalAvailable(List.of(actionId));
        DecisionPackage current = requirePackage(packageId);
        PackageItem oldItem = findItem(current, index);
        PlayerAction oldAction = requirePackageAction(oldItem, packageId);
        PlayerAction replacementAction = requireQueued(actionId);
        if (oldAction.actionId().equals(replacementAction.actionId())) {
            throw new ActionPackageException(ActionPackageException.INVALID_ARGUMENTS,
                    "替换行动不能与旧行动相同");
        }
        PackageItem replacement = toItem(index, titleFor(packageId), replacementAction);
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.REPLACE_ITEM,
                packageId, gm.actorId(), gm.role(), titleFor(packageId), current.proposerKey(),
                List.of(replacement), index, null, null, "", current.deadlineEpochMs(),
                null, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionPackage replaced = packages.replaceItem(packageId, index, replacement);
            syncProjectionBestEffort(() -> {
                actions.markSelected(replacementAction.actionId(), packageId);
                if (oldAction.status() == ActionStatus.SELECTED) {
                    actions.releaseSelected(oldAction.actionId());
                }
            });
            markApplied(trace, command);
            return summaryView(replaced);
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
    }

    /** 仅在 PROPOSED 且尚无逐条表态时删除；删空包由 AgentLib 拒绝且不触碰行动池。 */
    public synchronized PackageView removeItem(Actor gm, String packageId, int index) {
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
        requireGm(gm);
        requireText(packageId, "packageId");
        DecisionPackage current = requirePackage(packageId);
        PackageItem oldItem = findItem(current, index);
        PlayerAction oldAction = requirePackageAction(oldItem, packageId);
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.REMOVE_ITEM,
                packageId, gm.actorId(), gm.role(), titleFor(packageId), current.proposerKey(),
                List.of(), index, null, null, "", current.deadlineEpochMs(),
                null, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionPackage removed = packages.removeItem(packageId, index);
            syncProjectionBestEffort(() -> {
                if (oldAction.status() == ActionStatus.SELECTED) {
                    actions.releaseSelected(oldAction.actionId());
                }
            });
            markApplied(trace, command);
            return summaryView(removed);
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
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
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
        requireGm(gm);
        requireText(packageId, "packageId");
        Set<Integer> approved = approvedIndexes == null ? Set.of() : Set.copyOf(approvedIndexes);
        DecisionPackage current = requirePackage(packageId);
        String decisionNote = note == null ? "" : note;
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.DECIDE,
                packageId, gm.actorId(), gm.role(), titleFor(packageId), current.proposerKey(),
                List.of(), null, approved, null, decisionNote, current.deadlineEpochMs(),
                null, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionPackage decided = packages.decide(packageId,
                    new Decision(approved, gm.actorId(), decisionNote));
            markApplied(trace, command);
            return summaryView(decided);
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
    }

    /** 逐条裁决；首次表态后包进入 DECIDING，内容编辑由 AgentLib 冻结。 */
    public synchronized PackageView decideItem(Actor gm, String packageId, int index,
                                               boolean approve, String note) {
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
        requireGm(gm);
        requireText(packageId, "packageId");
        DecisionPackage current = requirePackage(packageId);
        String decisionNote = note == null ? "" : note;
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.DECIDE_ITEM,
                packageId, gm.actorId(), gm.role(), titleFor(packageId), current.proposerKey(),
                List.of(), index, null, approve, decisionNote, current.deadlineEpochMs(),
                null, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionPackages.ItemDecisionResult result = packages.itemDecided(packageId, index,
                    approve, gm.actorId(), decisionNote);
            markApplied(trace, command);
            return summaryView(result.pkg());
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
    }

    /**
     * 提交已批子集并形成行动批次。依赖 AgentLib 保证同一个包只执行一次；第二次调用返回
     * 首次回执且不会再次调用执行器。未批准条目在成功提交后释放回 QUEUED（明确保留，未执行）。
     */
    public synchronized CommitReceipt commit(Actor gm, String packageId,
                                             ActionBatchExecutor executor) {
        ensureJournalWritable();
        reconcileBeforeWriteBestEffort();
        requireGm(gm);
        requireText(packageId, "packageId");
        Objects.requireNonNull(executor, "executor");
        DecisionPackage current = requirePackage(packageId);
        // 已归一化的 receipt 优先返回，确保 duplicate commit 不回退到 null revisionRef。
        CommitReceipt knownReceipt = normalizedReceipts.get(packageId);
        if (knownReceipt != null) {
            return knownReceipt;
        }
        // 已有 settled receipt 时直接回放，禁止再次 STARTED/APPLIED/BATCH_COMPLETED/executor。
        Optional<DecisionPackageSnapshot> settled = packages.snapshot(packageId);
        if (settled.isPresent() && settled.get().settled()
                && settled.get().receipt() != null) {
            ActionBatch batch = resolveCommitBatch(packageId, settled.get().receipt());
            CommitReceipt normalized = normalizeReceiptRevision(
                    settled.get().receipt(), batch);
            normalizedReceipts.put(packageId, normalized);
            return normalized;
        }
        validateCanonicalForCommit(current);
        PackageCommand command = new PackageCommand(PackageCommand.CommandType.COMMIT,
                packageId, gm.actorId(), gm.role(), titleFor(packageId), current.proposerKey(),
                List.of(), null, null, null, "", current.deadlineEpochMs(),
                null, null, null);
        CommandTrace trace = begin(gm, command);
        try {
            DecisionCommitPort port = (id, calls) -> executeApproved(id, calls, executor);
            CommitReceipt committedReceipt = packages.commit(packageId, port);
            syncProjectionBestEffort(() ->
                    reconcileActionStates(packageId, committedReceipt));
            ActionBatch batch = resolveCommitBatch(packageId, committedReceipt);
            CommitReceipt receipt = normalizeReceiptRevision(committedReceipt, batch);
            normalizedReceipts.put(packageId, receipt);
            PackageCommand applied = command.withCommitOutcome(receipt, batch);
            markApplied(trace, applied);
            // P5 验收要求每个首次产生的 batch（COMMITTED/REJECTED/CONFLICTED）都有
            // BATCH_COMPLETED；同 batchId 由集合保证只写一次。
            appendBatchCompleted(batch);
            return receipt;
        } catch (RuntimeException failure) {
            markFailedUnlessJournalFailStop(trace, failure);
            throw failure;
        }
    }

    /** 已产生的批次只读列表（按提交顺序）。 */
    public synchronized List<ActionBatch> batches() {
        return List.copyOf(batches);
    }

    private void validateCanonicalForCommit(DecisionPackage current) {
        Map<String, CanonicalOwner> owners;
        try {
            owners = canonicalOwners();
        } catch (ActionPackageException failure) {
            throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                    "canonical 包 action owner 重复，拒绝 commit", failure);
        }
        Set<String> packageActionIds = new LinkedHashSet<>();
        for (PackageItem item : current.items()) {
            String actionId = actionId(item);
            if (actionId == null) {
                throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                        "包条目缺少 actionId，拒绝 commit");
            }
            if (!packageActionIds.add(actionId)) {
                throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                        "本包 actionId 重复，拒绝 commit");
            }
            CanonicalOwner owner = owners.get(actionId);
            if (owner == null || !owner.packageId().equals(current.id())) {
                throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                        "action canonical owner 不属于本包，拒绝 commit");
            }
            Set<Integer> approved = current.decision() == null
                    ? Set.of() : current.decision().approvedIndexes();
            if (approved.contains(item.index())) {
                PlayerAction action = actions.getForGm(actionId).orElseThrow(() ->
                        new ActionPackageException(ActionPackageException.INVALID_STATUS,
                                "本包批准行动不存在，拒绝 commit"));
                if (action.status() == ActionStatus.WITHDRAWN
                        || action.status() == ActionStatus.COMMITTED) {
                    throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                            "本包批准行动处于不可用状态，拒绝 commit");
                }
            }
        }
    }

    private CommitReceipt executeApproved(String packageId, List<ResolvedCall> calls,
                                          ActionBatchExecutor executor) {
        DecisionPackage currentPackage = requirePackage(packageId);
        Map<String, CanonicalOwner> owners;
        try {
            owners = canonicalOwners();
        } catch (ActionPackageException failure) {
            return rejected(calls, "canonical 包 action owner 重复");
        }
        Set<String> packageActionIds = new LinkedHashSet<>();
        for (PackageItem item : currentPackage.items()) {
            String itemActionId = actionId(item);
            if (itemActionId == null || !packageActionIds.add(itemActionId)) {
                return rejected(calls, "本包 actionId 缺失或重复");
            }
        }
        Set<String> seenCalls = new LinkedHashSet<>();
        List<PlayerAction> resolved = new ArrayList<>(calls.size());
        for (ResolvedCall call : calls) {
            if (!TOOL_NAME.equals(call.toolName())) {
                return rejected(calls, "包内条目工具名不是 " + TOOL_NAME);
            }
            String actionId = requireArg(call.args(), "actionId");
            if (!seenCalls.add(actionId)) {
                return rejected(calls, "executor 调用 actionId 重复");
            }
            if (!packageActionIds.contains(actionId)) {
                return rejected(calls, "action 不属于当前包");
            }
            CanonicalOwner owner = owners.get(actionId);
            if (owner == null || !owner.packageId().equals(packageId)) {
                return rejected(calls, "action canonical owner 不属于当前包");
            }
            Optional<PlayerAction> found = actions.getForGm(actionId);
            if (found.isEmpty()) {
                return rejected(calls, "包内行动已不存在");
            }
            PlayerAction action = found.get();
            // 投影可滞后为 QUEUED；WITHDRAWN/COMMITTED 等不可用状态必须拒绝。
            if (action.status() == ActionStatus.WITHDRAWN
                    || action.status() == ActionStatus.COMMITTED) {
                return rejected(calls, "包内行动处于不可用状态: " + action.status());
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
            ActionBatch failed = pending.withStatus(ActionBatchStatus.REJECTED,
                    "行动批次执行器失败，未标记为已提交");
            addBatchIfAbsent(failed);
            return new CommitReceipt(CommitReceipt.Status.REJECTED, failed.batchId(),
                    notCommitted(calls.size()), "行动批次执行器失败，未标记为已提交");
        }
        if (finished == null) {
            ActionBatch failed = pending.withStatus(ActionBatchStatus.REJECTED,
                    "行动批次执行器返回 null");
            addBatchIfAbsent(failed);
            return new CommitReceipt(CommitReceipt.Status.REJECTED, failed.batchId(),
                    notCommitted(calls.size()), "行动批次执行器返回 null");
        }
        if (finished.status() != ActionBatchStatus.COMMITTED) {
            addBatchIfAbsent(finished);
            CommitReceipt.Status status = finished.status() == ActionBatchStatus.CONFLICTED
                    ? CommitReceipt.Status.CONFLICTED : CommitReceipt.Status.REJECTED;
            return new CommitReceipt(status, finished.batchId(), notCommitted(calls.size()),
                    "行动批次未以 COMMITTED 结束: " + finished.status());
        }

        for (PlayerAction action : resolved) {
            syncProjectionBestEffort(() -> {
                PlayerAction current = actions.getForGm(action.actionId()).orElse(null);
                if (current != null && current.status() == ActionStatus.QUEUED) {
                    actions.markSelected(action.actionId(), packageId);
                }
                actions.markCommitted(action.actionId());
            });
        }
        addBatchIfAbsent(finished);
        return new CommitReceipt(CommitReceipt.Status.COMMITTED, finished.batchId(),
                Collections.nCopies(calls.size(), CommitReceipt.ItemOutcome.COMMITTED),
                finished.result() == null ? "行动批次已提交" : finished.result());
    }

    private static CommitReceipt normalizeReceiptRevision(CommitReceipt receipt,
                                                          ActionBatch batch) {
        if (receipt == null || batch == null
                || (receipt.revisionRef() != null && !receipt.revisionRef().isBlank())) {
            return receipt;
        }
        return new CommitReceipt(receipt.status(), batch.batchId(), receipt.outcomes(),
                receipt.reason());
    }

    private boolean addBatchIfAbsent(ActionBatch batch) {
        if (batch == null) {
            return false;
        }
        for (ActionBatch existing : batches) {
            if (existing.batchId().equals(batch.batchId())) {
                if (!existing.equals(batch)) {
                    throw new ActionPackageException(
                            ActionPackageException.PACKAGE_ACTION_RECONCILE_FAILED,
                            "重复 batchId 对应不同批次内容");
                }
                return false;
            }
        }
        batches.add(batch);
        return true;
    }

    /**
     * 找到本次 commit 对应的批次。正常提交/执行器返回非 COMMITTED 时 batches 已有记录；
     * 若 AgentLib 在调用执行器前就拒绝，则生成仅用于命令审计的空批次。
     */
    private ActionBatch resolveCommitBatch(String packageId, CommitReceipt receipt) {
        if (receipt == null) {
            return null;
        }
        if (receipt.revisionRef() != null && !receipt.revisionRef().isBlank()) {
            for (ActionBatch batch : batches) {
                if (batch.batchId().equals(receipt.revisionRef())) {
                    return batch;
                }
            }
        }
        for (int index = batches.size() - 1; index >= 0; index--) {
            ActionBatch batch = batches.get(index);
            if (batch.packageId().equals(packageId)) {
                return batch;
            }
        }
        ActionBatchStatus status = switch (receipt.status()) {
            case COMMITTED -> ActionBatchStatus.COMMITTED;
            case CONFLICTED -> ActionBatchStatus.CONFLICTED;
            case REJECTED -> ActionBatchStatus.REJECTED;
        };
        ActionBatch fallback = new ActionBatch("batch-" + UUID.randomUUID(), packageId, null,
                List.of(), List.of(), List.of(), clock.instant(), status, receipt.reason());
        addBatchIfAbsent(fallback);
        return fallback;
    }

    /**
     * 从 canonical package 状态推导 ActionPool 投影。启动恢复必须成功；运行期普通写
     * 调用走 {@link #syncProjectionBestEffort(Runnable)}，只记 pending，不影响包成功语义。
     */
    /**
     * GM 侧忽略 action 的 canonical 临界区入口。
     *
     * <p>canonical owner 检查、ActionPool withdraw 与所有 package 写操作共享同一
     * ActionPackageService 监视器，避免 ignore 与 create/append/commit 的 TOCTOU。</p>
     */
    public synchronized PlayerAction ignoreAction(Actor gm, String actionId,
                                                   String idempotencyKey) {
        requireGm(gm);
        requireText(actionId, "actionId");
        requireText(idempotencyKey, "idempotencyKey");
        if (canonicalOwners().containsKey(actionId)) {
            throw new io.mosire.iibot.taskflow.action.ActionPoolException(
                    io.mosire.iibot.taskflow.action.ActionPoolException
                            .ACTION_IN_CANONICAL_PACKAGE,
                    "行动已被 canonical 裁定包占用，不能 ignore");
        }
        PlayerAction current = actions.getForGm(actionId).orElseThrow(() ->
                new io.mosire.iibot.taskflow.action.ActionPoolException(
                        io.mosire.iibot.taskflow.action.ActionPoolException.NOT_FOUND,
                        "行动不存在: " + actionId));
        return actions.withdraw(new Actor(current.playerId(), ActorRole.PLAYER),
                actionId, idempotencyKey);
    }

    /** reason 仅作为调用方审计上下文，不改变 canonical/withdraw 语义。 */
    public synchronized PlayerAction ignoreAction(Actor gm, String actionId,
                                                   String idempotencyKey, String reason) {
        return ignoreAction(gm, actionId, idempotencyKey);
    }

    /**
     * 只读查询 action 的 canonical package owner。active 包占用全部 item，COMMITTED
     * 只占用 approved item；terminal 包不占用。用于宿主侧阻止 action 撤回绕过包事实。
     */
    public synchronized Optional<String> canonicalOwnerOf(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(canonicalOwners().get(actionId))
                .map(CanonicalOwner::packageId);
    }

    /** canonical owner 只读布尔视图。 */
    public synchronized boolean isCanonicallyOwned(String actionId) {
        return canonicalOwnerOf(actionId).isPresent();
    }

    /**
     * canonical owner 唯一性扫描：active 包占用全部 item，COMMITTED 只占用 approved item；
     * terminal 包不占用。同一 action 被重复占用即包事实损坏，fail-closed。
     */
    private Map<String, CanonicalOwner> canonicalOwners() {
        Map<String, CanonicalOwner> owners = new LinkedHashMap<>();
        for (DecisionPackage pkg : packages.all()) {
            boolean active = pkg.state() == PackageState.PROPOSED
                    || pkg.state() == PackageState.DECIDING
                    || pkg.state() == PackageState.DECIDED;
            boolean committed = pkg.state() == PackageState.COMMITTED;
            if (!active && !committed) {
                continue;
            }
            Set<Integer> approved = pkg.decision() == null
                    ? Set.of() : pkg.decision().approvedIndexes();
            for (PackageItem item : pkg.items()) {
                if (committed && !approved.contains(item.index())) {
                    continue;
                }
                String actionId = actionId(item);
                if (actionId == null) {
                    throw reconcileFailure("包条目缺少 actionId");
                }
                CanonicalOwner owner = new CanonicalOwner(pkg.id(), item.index());
                CanonicalOwner previous = owners.putIfAbsent(actionId, owner);
                if (previous != null) {
                    throw reconcileFailure("同一 action 被多个 canonical 包/条目占用");
                }
            }
        }
        return owners;
    }

    private void requireCanonicalAvailable(List<String> actionIds) {
        Map<String, CanonicalOwner> owners;
        try {
            owners = canonicalOwners();
        } catch (ActionPackageException failure) {
            throw new ActionPackageException(ActionPackageException.CONFLICT,
                    "canonical 包事实损坏，拒绝继续组包", failure);
        }
        for (String actionId : actionIds) {
            if (owners.containsKey(actionId)) {
                throw new ActionPackageException(ActionPackageException.CONFLICT,
                        "action 已被 canonical 包占用: " + actionId);
            }
        }
    }

    public synchronized void reconcileActionsFromPackages() {
        try {
            Map<String, CanonicalOwner> owners = canonicalOwners();
            Map<String, ProjectionTarget> desired = new LinkedHashMap<>();
            for (Map.Entry<String, CanonicalOwner> entry : owners.entrySet()) {
                DecisionPackage pkg = packages.find(entry.getValue().packageId())
                        .orElseThrow(() -> reconcileFailure("canonical owner 包不存在"));
                if (pkg.state() == PackageState.COMMITTED) {
                    desired.put(entry.getKey(),
                            new ProjectionTarget(ActionStatus.COMMITTED, pkg.id()));
                } else {
                    desired.put(entry.getKey(),
                            new ProjectionTarget(ActionStatus.SELECTED, pkg.id()));
                }
            }

            Set<String> seen = new LinkedHashSet<>();
            for (PlayerAction actual : actions.listForGm(null)) {
                seen.add(actual.actionId());
                ProjectionTarget target = desired.getOrDefault(actual.actionId(),
                        new ProjectionTarget(ActionStatus.QUEUED, null));
                applyProjection(actual, target);
            }
            if (!seen.containsAll(desired.keySet())) {
                throw reconcileFailure("ActionPool 缺少 canonical 包内 action");
            }
            actionReconcilePending = false;
        } catch (ActionPackageException failure) {
            actionReconcilePending = true;
            throw failure;
        } catch (RuntimeException failure) {
            actionReconcilePending = true;
            throw new ActionPackageException(
                    ActionPackageException.PACKAGE_ACTION_RECONCILE_FAILED,
                    "ActionPool 投影重建失败", failure);
        }
    }

    private void applyProjection(PlayerAction actual, ProjectionTarget target) {
        switch (target.status()) {
            case COMMITTED -> {
                if (actual.status() == ActionStatus.COMMITTED) {
                    return;
                }
                if (actual.status() == ActionStatus.SELECTED
                        && !target.packageId().equals(actual.packageId())) {
                    actions.releaseSelected(actual.actionId());
                }
                if (actual.status() == ActionStatus.QUEUED) {
                    actions.markSelected(actual.actionId(), target.packageId());
                }
                actions.markCommitted(actual.actionId());
            }
            case SELECTED -> {
                if (actual.status() == ActionStatus.SELECTED
                        && target.packageId().equals(actual.packageId())) {
                    return;
                }
                if (actual.status() == ActionStatus.SELECTED) {
                    actions.releaseSelected(actual.actionId());
                }
                if (actual.status() == ActionStatus.QUEUED) {
                    actions.markSelected(actual.actionId(), target.packageId());
                    return;
                }
                throw reconcileFailure("action 无法投影为 SELECTED");
            }
            case QUEUED -> {
                if (actual.status() == ActionStatus.QUEUED
                        || actual.status() == ActionStatus.WITHDRAWN) {
                    return;
                }
                if (actual.status() == ActionStatus.SELECTED) {
                    actions.releaseSelected(actual.actionId());
                    return;
                }
                throw reconcileFailure("action 无法投影回 QUEUED");
            }
            default -> throw reconcileFailure("未知投影目标状态");
        }
    }

    private static ActionPackageException reconcileFailure(String message) {
        return new ActionPackageException(
                ActionPackageException.PACKAGE_ACTION_RECONCILE_FAILED, message);
    }

    /** ActionPool 投影失败不得影响 canonical 包/批次命令的 APPLIED/成功语义。 */
    private void syncProjectionBestEffort(Runnable projection) {
        try {
            projection.run();
        } catch (RuntimeException failure) {
            actionReconcilePending = true;
            LOG.error("ActionPool 投影同步失败，已标记 pending reconcile type={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** 运行期每次包写前 best-effort 修复投影；失败不阻塞当前 canonical 包操作。 */
    private void reconcileBeforeWriteBestEffort() {
        try {
            reconcileActionsFromPackages();
        } catch (RuntimeException failure) {
            actionReconcilePending = true;
            LOG.error("ActionPool reconcile 失败，保持 pending type={}",
                    failure.getClass().getSimpleName());
        }
    }

    private record ProjectionTarget(ActionStatus status, String packageId) {
    }

    private record CanonicalOwner(String packageId, int index) {
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

    /** 兼容入口：不注入 journal 的恢复结果只读可用，但后续写操作不追加命令事件。 */
    public static ActionPackageService recover(ActionPoolService actions, Clock clock,
                                                List<PackageCommandEvent> events) {
        return recover(actions, clock, events, null);
    }

    /**
     * 从 PACKAGE_COMMAND_* 事件恢复 AgentLib 内存包状态。
     *
     * <p>只重放 APPLIED；STARTED 必须由 APPLIED 或 FAILED 收口。恢复不调用行动执行器，
     * COMMIT 直接回放已持久化的 CommitReceipt。</p>
     */
    public static ActionPackageService recover(ActionPoolService actions, Clock clock,
                                                List<PackageCommandEvent> events,
                                                DomainEventJournal journal) {
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(events, "events");
        if (events.size() > MAX_RECOVERY_EVENTS) {
            throw new ActionPackageException(ActionPackageException.PACKAGE_RECOVERY_INCOMPLETE,
                    "包命令事件数量超过恢复上限");
        }
        DecisionPackages fresh = new DecisionPackages();
        ActionPackageService service = new ActionPackageService(actions, fresh, clock, journal);
        List<PackageCommandEvent> applied = new ArrayList<>();
        PackageCommandEvent pending = null;
        PackageCommandEvent lastApplied = null;
        for (PackageCommandEvent event : events) {
            Objects.requireNonNull(event, "event");
            switch (event.phase()) {
                case STARTED -> {
                    if (pending != null) {
                        throw recoveryIncomplete("STARTED 未收口即出现下一 STARTED");
                    }
                    pending = event;
                    lastApplied = null;
                }
                case FAILED -> {
                    if (pending == null
                            || !sameCommandBase(pending.command(), event.command())) {
                        throw recoveryIncomplete("FAILED 缺少匹配的 STARTED");
                    }
                    pending = null;
                    lastApplied = null;
                }
                case APPLIED -> {
                    if (pending == null && lastApplied != null
                            && sameCommandBase(lastApplied.command(), event.command())) {
                        // 重复 APPLIED 直接忽略；批次仍由 batchId 去重。
                        continue;
                    }
                    if (pending == null
                            || !sameCommandBase(pending.command(), event.command())) {
                        throw recoveryIncomplete("APPLIED 缺少匹配的 STARTED");
                    }
                    applied.add(event);
                    pending = null;
                    lastApplied = event;
                }
            }
        }
        if (pending != null) {
            throw recoveryIncomplete("存在只有 STARTED 无 APPLIED/FAILED 的命令");
        }

        long nowMillis = clock.millis();
        long replayDeadlineFloor = safeAdd(nowMillis, RECOVERY_DEADLINE_FLOOR_MILLIS);
        Map<String, Long> originalDeadlines = new LinkedHashMap<>();
        for (PackageCommandEvent event : applied) {
            PackageCommand command = event.command();
            switch (command.commandType()) {
                case CREATE -> {
                    // 未来 deadline 必须精确保留；仅已过期命令使用 now+1h 防回放时立即过期。
                    long deadline = command.deadlineEpochMs() > nowMillis
                            ? command.deadlineEpochMs()
                            : Math.max(command.deadlineEpochMs(), replayDeadlineFloor);
                    ProposeRequest request = new ProposeRequest(command.proposerKey(),
                            AccessToken.DEFAULT, command.items(), deadline);
                    fresh.propose(command.packageId(), request);
                    if (command.title() != null && !command.title().isBlank()) {
                        service.titles.put(command.packageId(), command.title());
                    }
                    if (command.idempotencyKey() != null
                            && !command.idempotencyKey().isBlank()) {
                        service.createIdempotency.put(
                                command.actorId() + CREATE_KEY_SEPARATOR
                                        + command.idempotencyKey(), command.packageId());
                    }
                    originalDeadlines.put(command.packageId(), command.deadlineEpochMs());
                }
                case APPEND -> fresh.append(command.packageId(), command.items());
                case REPLACE_ITEM -> {
                    if (command.index() == null || command.items().size() != 1) {
                        throw recoveryIncomplete("REPLACE_ITEM 缺少 index 或 replacement");
                    }
                    fresh.replaceItem(command.packageId(), command.index(),
                            command.items().get(0));
                }
                case REMOVE_ITEM -> {
                    if (command.index() == null) {
                        throw recoveryIncomplete("REMOVE_ITEM 缺少 index");
                    }
                    fresh.removeItem(command.packageId(), command.index());
                }
                case DECIDE -> fresh.decide(command.packageId(), new Decision(
                        command.approvedIndexes(), command.actorId(), command.note()));
                case DECIDE_ITEM -> {
                    if (command.index() == null || command.approve() == null) {
                        throw recoveryIncomplete("DECIDE_ITEM 缺少 index/approve");
                    }
                    fresh.itemDecided(command.packageId(), command.index(),
                            command.approve(), command.actorId(), command.note());
                }
                case COMMIT -> {
                    validateRecoveryCommit(command, fresh);
                    fresh.commit(command.packageId(),
                            (id, calls) -> command.receipt());
                    service.addBatchIfAbsent(command.batch());
                    service.batchCompletedWritten.add(command.batch().batchId());
                    service.normalizedReceipts.put(command.packageId(),
                            command.receipt());
                }
            }
        }

        // 回放时使用 now+1h 防即时过期；回放结束后再按原始 deadline 恢复真实过期态。
        for (Map.Entry<String, Long> entry : originalDeadlines.entrySet()) {
            if (entry.getValue() >= nowMillis) {
                continue;
            }
            DecisionPackage pkg = fresh.find(entry.getKey()).orElse(null);
            if (pkg != null && !isTerminal(pkg.state())) {
                fresh.expireNow(entry.getKey(), nowMillis);
            }
        }
        // 启动恢复末段把 ActionPool 投影校正到 package/batch canonical 状态；失败即启动失败。
        service.reconcileActionsFromPackages();
        return service;
    }

    private static void validateRecoveryCommit(PackageCommand command,
                                                DecisionPackages fresh) {
        if (command.receipt() == null || command.batch() == null) {
            throw recoveryIncomplete("COMMIT APPLIED 缺少 receipt/batch");
        }
        ActionBatch batch = command.batch();
        if (!command.packageId().equals(batch.packageId())) {
            throw recoveryIncomplete("COMMIT batch.packageId 不一致");
        }
        if (command.receipt().revisionRef() == null
                || !command.receipt().revisionRef().equals(batch.batchId())) {
            throw recoveryIncomplete("COMMIT receipt.revisionRef 与 batchId 不一致");
        }
        DecisionPackage pkg = fresh.find(command.packageId()).orElseThrow(() ->
                recoveryIncomplete("COMMIT 对应包不存在"));
        Set<Integer> approved = pkg.decision() == null
                ? Set.of() : pkg.decision().approvedIndexes();
        List<String> expectedActionIds = new ArrayList<>();
        for (PackageItem item : pkg.items()) {
            if (approved.contains(item.index())) {
                String actionId = actionId(item);
                if (actionId != null) {
                    expectedActionIds.add(actionId);
                }
            }
        }
        expectedActionIds.sort(String::compareTo);
        List<String> batchActionIds = new ArrayList<>(batch.actionIds());
        batchActionIds.sort(String::compareTo);
        if (!batchActionIds.isEmpty() && !batchActionIds.equals(expectedActionIds)) {
            throw recoveryIncomplete("COMMIT batch.actionIds 与批准条目不一致");
        }
        if (command.receipt().status() == CommitReceipt.Status.COMMITTED
                && batch.status() != ActionBatchStatus.COMMITTED) {
            throw recoveryIncomplete("COMMITTED receipt 对应 batch 状态不一致");
        }
    }

    private static ActionPackageException recoveryIncomplete(String message) {
        return new ActionPackageException(ActionPackageException.PACKAGE_RECOVERY_INCOMPLETE,
                "[PACKAGE_RECOVERY_INCOMPLETE] " + message);
    }

    private static boolean sameCommandBase(PackageCommand start, PackageCommand applied) {
        return start.commandType() == applied.commandType()
                && Objects.equals(start.packageId(), applied.packageId())
                && Objects.equals(start.actorId(), applied.actorId())
                && start.actorRole() == applied.actorRole()
                && Objects.equals(start.title(), applied.title())
                && Objects.equals(start.proposerKey(), applied.proposerKey())
                && Objects.equals(start.items(), applied.items())
                && Objects.equals(start.index(), applied.index())
                && Objects.equals(start.approvedIndexes(), applied.approvedIndexes())
                && Objects.equals(start.approve(), applied.approve())
                && Objects.equals(start.note(), applied.note())
                && start.deadlineEpochMs() == applied.deadlineEpochMs()
                && Objects.equals(start.idempotencyKey(), applied.idempotencyKey());
    }

    private static boolean isTerminal(PackageState state) {
        return state == PackageState.COMMITTED
                || state == PackageState.REJECTED
                || state == PackageState.CONFLICTED
                || state == PackageState.EXPIRED;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private CommandTrace begin(Actor actor, PackageCommand command) {
        ensureJournalWritable();
        if (journal == null) {
            return new CommandTrace(false, actor, command);
        }
        try {
            appendCommandEvent(DomainEventTypes.PACKAGE_COMMAND_STARTED,
                    new PackageCommandEvent(PackageCommandEvent.Phase.STARTED,
                            command, null));
        } catch (RuntimeException failure) {
            // STARTED 失败时尚未触碰内存；本次操作 fail-closed，但 journal 本身可重试。
            throw journalUnavailable(failure);
        }
        return new CommandTrace(true, actor, command);
    }

    /** APPLIED 写失败即 fail-stop；调用方必须明确本次内存操作已经发生。 */
    private void markApplied(CommandTrace trace, PackageCommand appliedCommand) {
        if (!trace.enabled()) {
            return;
        }
        try {
            appendCommandEvent(DomainEventTypes.PACKAGE_COMMAND_APPLIED,
                    new PackageCommandEvent(PackageCommandEvent.Phase.APPLIED,
                            appliedCommand, null));
        } catch (RuntimeException failure) {
            journalFailed = true;
            throw journalUnavailable(failure);
        }
    }

    private void markFailedUnlessJournalFailStop(CommandTrace trace, RuntimeException failure) {
        if (isJournalFailStop(failure)) {
            return;
        }
        markFailedBestEffort(trace, failure);
    }

    private static boolean isJournalFailStop(RuntimeException failure) {
        return failure instanceof ActionPackageException typed
                && EVENT_JOURNAL_UNAVAILABLE.equals(typed.code());
    }

    private void markFailedBestEffort(CommandTrace trace, RuntimeException failure) {
        if (!trace.enabled()) {
            return;
        }
        try {
            appendCommandEvent(DomainEventTypes.PACKAGE_COMMAND_FAILED,
                    new PackageCommandEvent(PackageCommandEvent.Phase.FAILED,
                            trace.command(), failureCode(failure)));
        } catch (RuntimeException journalFailure) {
            // 原失败语义优先；FAILED 只是尽力补审计。
            LOG.warn("PACKAGE_COMMAND_FAILED 事件写入失败 code={} type={}",
                    failureCode(failure), journalFailure.getClass().getSimpleName());
        }
    }

    private void appendBatchCompleted(ActionBatch batch) {
        if (batch == null || journal == null) {
            return;
        }
        if (!batchCompletedWritten.add(batch.batchId())) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("batchId", batch.batchId());
        summary.put("packageId", batch.packageId());
        summary.put("turnId", batch.turnId());
        summary.put("actionCount", batch.actionIds().size());
        summary.put("status", batch.status().name());
        try {
            long seq = journal.append(new DomainEvent(clock.instant(),
                    DomainEventTypes.BATCH_COMPLETED, ActorRole.SUPERVISOR,
                    PACKAGE_ACTOR, batch.packageId(), batch.turnId(),
                    EventVisibility.GM, batch, summary));
            if (seq <= 0) {
                throw new IllegalStateException("BATCH_COMPLETED journal seq 无效");
            }
        } catch (RuntimeException failure) {
            journalFailed = true;
            throw journalUnavailable(failure);
        }
    }

    private void ensureJournalWritable() {
        if (journal != null && journalFailed) {
            throw new ActionPackageException(EVENT_JOURNAL_UNAVAILABLE,
                    "包命令事件 journal 已进入 fail-stop，拒绝后续包写操作");
        }
    }

    private void appendCommandEvent(String eventType, PackageCommandEvent event) {
        Map<String, Object> summary = commandSummary(event);
        long seq = journal.append(new DomainEvent(clock.instant(), eventType,
                event.command().actorRole(), event.command().actorId(),
                event.command().packageId(), null, EventVisibility.GM, event, summary));
        if (seq <= 0) {
            throw new IllegalStateException("PACKAGE_COMMAND 事件 journal seq 无效");
        }
    }

    private static Map<String, Object> commandSummary(PackageCommandEvent event) {
        PackageCommand command = event.command();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("commandType", command.commandType().name());
        summary.put("phase", event.phase().name());
        summary.put("packageId", command.packageId());
        summary.put("actor", command.actorId());
        summary.put("actorRole", command.actorRole().name());
        summary.put("itemCount", command.items().size());
        summary.put("approvedCount", command.approvedIndexes().size());
        summary.put("batchId", command.batch() == null ? null : command.batch().batchId());
        summary.put("turnId", command.batch() == null ? null : command.batch().turnId());
        if (command.receipt() != null) {
            summary.put("receiptStatus", command.receipt().status().name());
        }
        if (command.idempotencyKey() != null) {
            summary.put("idempotencyKeyHash", io.mosire.iibot.taskflow.Hashes.sha256(
                    command.idempotencyKey()));
        }
        return summary;
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof ActionPackageException typed) {
            return typed.code();
        }
        if (failure instanceof DecisionPackages.RejectionException typed) {
            return typed.code();
        }
        return "PACKAGE_COMMAND_FAILED";
    }

    private static ActionPackageException journalUnavailable(RuntimeException cause) {
        return new ActionPackageException(EVENT_JOURNAL_UNAVAILABLE,
                "包命令事件写入失败，服务进入 fail-stop", cause);
    }

    private record CommandTrace(boolean enabled, Actor actor, PackageCommand command) {
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
        boolean projectionUsable = action.status() == ActionStatus.QUEUED
                || (action.status() == ActionStatus.SELECTED
                && packageId.equals(action.packageId()));
        if (!projectionUsable) {
            throw new ActionPackageException(ActionPackageException.INVALID_STATUS,
                    "包内行动处于不可用投影状态: " + actionId);
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
