package io.mosire.iibot.taskflow.action;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨回合自然语言行动池。
 *
 * <p>提交与撤回永不读取回合状态：玩家/秘书可以在任何时间提交自然语言行动。行动是否进入
 * 裁定包完全由 GM 侧 {@code ActionPackageService} 决定。所有写操作采用 copy-on-write：
 * 先在不可变快照上构造完整新值，{@link ActionPoolStore#save(ActionPoolSnapshot)} 成功后才
 * 替换进程内快照；落盘失败时内存状态保持不变。</p>
 *
 * <p>包生命周期相关方法 {@link #markSelected(String, String)}、{@link #releaseSelected(String)}
 * 与 {@link #markCommitted(String)} 是宿主裁定包服务的专用写口，普通玩家工具不暴露它们。</p>
 */
public final class ActionPoolService {

    public static final int MAX_TEXT_BYTES = 16 * 1024;

    private static final String PACKAGE_ACTOR = "system:action-package";
    private static final String TICKET_ACTOR = "system:action-ticket";
    private static final String TICKET_SOURCE_PREFIX = "ticket:";

    private final ActionPoolStore store;
    private final Clock clock;
    private ActionPoolSnapshot snapshot;

    public ActionPoolService(Clock clock) {
        this(new InMemoryActionPoolStore(), clock);
    }

    public ActionPoolService(ActionPoolStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        ActionPoolSnapshot loaded = store.load();
        if (loaded == null) {
            throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                    "行动池存储返回了 null 快照");
        }
        this.snapshot = loaded;
    }

    /**
     * 提交行动。只接受已认证 PLAYER，playerId 永远取 actorId；同一玩家重复使用同一
     * idempotencyKey 返回首次结果（即便正文不同）。
     */
    public synchronized PlayerAction submit(Actor player, String text, String source,
                                            String idempotencyKey) {
        requirePlayer(player);
        requireText(text, "text");
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > MAX_TEXT_BYTES) {
            throw new ActionPoolException(ActionPoolException.INVALID_ARGUMENTS,
                    "text 必须为 1.." + MAX_TEXT_BYTES + " UTF-8 字节");
        }
        requireText(source, "source");
        requireText(idempotencyKey, "idempotencyKey");

        Optional<String> previousId = snapshot.idempotentActionId(player.actorId(), idempotencyKey);
        if (previousId.isPresent()) {
            PlayerAction previous = findAction(previousId.get());
            if (!previous.playerId().equals(player.actorId())) {
                throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                        "行动池幂等索引指向了其他玩家的行动");
            }
            return previous;
        }

        Instant now = clock.instant();
        PlayerAction action = new PlayerAction("act-" + UUID.randomUUID(), player.actorId(),
                text, ActionStatus.QUEUED, null, now, now, source, idempotencyKey);
        List<PlayerAction> actions = new ArrayList<>(snapshot.actions());
        actions.add(action);
        Map<String, Map<String, String>> idempotent = copyIdempotentResults(snapshot);
        idempotent.put(player.actorId(), withResult(idempotent.get(player.actorId()),
                idempotencyKey, action.actionId()));
        ActionPoolSnapshot next = new ActionPoolSnapshot(actions, idempotent);
        save(next, actionEvent(now, DomainEventTypes.ACTION_SUBMITTED,
                player.role(), player.actorId(), action, next, idempotencyKey));
        return action;
    }

    /**
     * 宿主/GM 晋升专用：从 ACTION_DRAFT ticket 创建 QUEUED action。
     *
     * <p>不检查回合状态；source 固定为 {@code ticket:<ticketId>}，同一 ticket 重复调用返回
     * 同一 action。actor 是明确的宿主 system actor，不是模型可控身份。</p>
     */
    public synchronized PlayerAction submitFromTicket(String playerId, String ticketId,
                                                       String text, String idempotencyKey) {
        requireText(playerId, "playerId");
        requireText(ticketId, "ticketId");
        requireText(text, "text");
        requireText(idempotencyKey, "idempotencyKey");
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > MAX_TEXT_BYTES) {
            throw new ActionPoolException(ActionPoolException.INVALID_ARGUMENTS,
                    "text 必须为 1.." + MAX_TEXT_BYTES + " UTF-8 字节");
        }
        String source = TICKET_SOURCE_PREFIX + ticketId;
        Optional<PlayerAction> linked = findViewById(ticketId);
        if (linked.isPresent()) {
            PlayerAction action = linked.get();
            if (!action.playerId().equals(playerId)) {
                throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                        "同一 ticket 的 action 属于其他玩家");
            }
            return action;
        }
        Optional<String> previousId = snapshot.idempotentActionId(playerId, idempotencyKey);
        if (previousId.isPresent()) {
            PlayerAction previous = findAction(previousId.get());
            if (source.equals(previous.source())) {
                return previous;
            }
            throw new ActionPoolException(ActionPoolException.CONFLICT,
                    "idempotencyKey 已被其他来源使用");
        }

        Instant now = clock.instant();
        PlayerAction action = new PlayerAction("act-" + UUID.randomUUID(), playerId,
                text, ActionStatus.QUEUED, null, now, now, source, idempotencyKey);
        List<PlayerAction> actions = new ArrayList<>(snapshot.actions());
        actions.add(action);
        Map<String, Map<String, String>> idempotent = copyIdempotentResults(snapshot);
        idempotent.put(playerId, withResult(idempotent.get(playerId),
                idempotencyKey, action.actionId()));
        ActionPoolSnapshot next = new ActionPoolSnapshot(actions, idempotent);
        save(next, actionEvent(now, DomainEventTypes.ACTION_SUBMITTED,
                ActorRole.SUPERVISOR, TICKET_ACTOR, action, next, idempotencyKey));
        return action;
    }

    /** 按 ticket source 查找已晋升行动；只读。 */
    public synchronized Optional<PlayerAction> findByTicketId(String ticketId) {
        requireText(ticketId, "ticketId");
        return findViewById(ticketId);
    }

    private Optional<PlayerAction> findViewById(String ticketId) {
        String source = TICKET_SOURCE_PREFIX + ticketId;
        return snapshot.actions().stream()
                .filter(action -> source.equals(action.source()))
                .findFirst();
    }

    /** 某玩家自己的全部行动（按提交顺序）。 */
    public synchronized List<PlayerAction> listForPlayer(String playerId) {
        requireText(playerId, "playerId");
        return snapshot.actions().stream()
                .filter(action -> action.playerId().equals(playerId))
                .toList();
    }

    /** GM 视角按状态筛选；status 为 null 时列出全部。 */
    public synchronized List<PlayerAction> listForGm(ActionStatus status) {
        if (status == null) {
            return List.copyOf(snapshot.actions());
        }
        return snapshot.actions().stream()
                .filter(action -> action.status() == status)
                .toList();
    }

    /** GM 视角全量列表。 */
    public synchronized List<PlayerAction> listForGm() {
        return listForGm(null);
    }

    public synchronized Optional<PlayerAction> getForGm(String actionId) {
        requireText(actionId, "actionId");
        return snapshot.action(actionId);
    }

    /**
     * 玩家撤回自己尚未被选中的 QUEUED 行动。SELECTED 已进入裁定包，必须先由包服务释放，
     * 玩家侧不能直接撤回。已经 WITHDRAWN 的同一行动按幂等成功返回。
     */
    public synchronized PlayerAction withdraw(Actor player, String actionId,
                                              String idempotencyKey) {
        requirePlayer(player);
        requireText(actionId, "actionId");
        requireText(idempotencyKey, "idempotencyKey");
        PlayerAction current = findAction(actionId);
        if (!current.playerId().equals(player.actorId())) {
            throw new ActionPoolException(ActionPoolException.FORBIDDEN,
                    "只能撤回自己的行动");
        }
        if (current.status() == ActionStatus.WITHDRAWN) {
            return current;
        }
        if (current.status() != ActionStatus.QUEUED) {
            throw new ActionPoolException(ActionPoolException.INVALID_STATUS,
                    "只有 QUEUED 行动可以撤回；当前状态=" + current.status());
        }
        Instant now = clock.instant();
        PlayerAction withdrawn = current.withStatus(ActionStatus.WITHDRAWN, null, now);
        ActionPoolSnapshot next = replaceAction(withdrawn);
        save(next, actionEvent(now, DomainEventTypes.ACTION_WITHDRAWN,
                player.role(), player.actorId(), withdrawn, next, idempotencyKey));
        return withdrawn;
    }

    /** 裁定包服务专用：QUEUED → SELECTED 并绑定 packageId。 */
    public synchronized PlayerAction markSelected(String actionId, String packageId) {
        requireText(actionId, "actionId");
        requireText(packageId, "packageId");
        PlayerAction current = findAction(actionId);
        if (current.status() != ActionStatus.QUEUED) {
            throw new ActionPoolException(ActionPoolException.INVALID_STATUS,
                    "只有 QUEUED 行动可以选中；当前状态=" + current.status());
        }
        Instant now = clock.instant();
        PlayerAction selected = current.withStatus(ActionStatus.SELECTED, packageId, now);
        ActionPoolSnapshot next = replaceAction(selected);
        save(next, actionEvent(now, DomainEventTypes.ACTION_SELECTED,
                ActorRole.SUPERVISOR, PACKAGE_ACTOR, selected, next, null));
        return selected;
    }

    /** 裁定包服务专用：SELECTED → QUEUED，并清空 packageId。 */
    public synchronized PlayerAction releaseSelected(String actionId) {
        requireText(actionId, "actionId");
        PlayerAction current = findAction(actionId);
        if (current.status() != ActionStatus.SELECTED) {
            throw new ActionPoolException(ActionPoolException.INVALID_STATUS,
                    "只有 SELECTED 行动可以释放；当前状态=" + current.status());
        }
        Instant now = clock.instant();
        PlayerAction released = current.withStatus(ActionStatus.QUEUED, null, now);
        ActionPoolSnapshot next = replaceAction(released);
        save(next, actionEvent(now, DomainEventTypes.ACTION_RELEASED,
                ActorRole.SUPERVISOR, PACKAGE_ACTOR, released, next, null));
        return released;
    }

    /** 裁定包服务专用：SELECTED → COMMITTED，保留 packageId 作为对账键。 */
    public synchronized PlayerAction markCommitted(String actionId) {
        requireText(actionId, "actionId");
        PlayerAction current = findAction(actionId);
        if (current.status() != ActionStatus.SELECTED) {
            throw new ActionPoolException(ActionPoolException.INVALID_STATUS,
                    "只有 SELECTED 行动可以提交；当前状态=" + current.status());
        }
        Instant now = clock.instant();
        PlayerAction committed = current.withStatus(ActionStatus.COMMITTED,
                current.packageId(), now);
        ActionPoolSnapshot next = replaceAction(committed);
        save(next, actionEvent(now, DomainEventTypes.ACTION_COMMITTED,
                ActorRole.SUPERVISOR, PACKAGE_ACTOR, committed, next, null));
        return committed;
    }

    /** 当前不可变快照（供 app 持久化/JSON 序列化；包含行动正文）。 */
    public synchronized ActionPoolSnapshot snapshot() {
        return snapshot;
    }

    private void save(ActionPoolSnapshot next, DomainEvent event) {
        try {
            store.save(next, event);
        } catch (RuntimeException failure) {
            throw new ActionPoolException(ActionPoolException.STORE_UNAVAILABLE,
                    "行动池保存失败，内存状态未替换", failure);
        }
        snapshot = next;
    }

    private static DomainEvent actionEvent(Instant now, String eventType, ActorRole actorRole,
                                           String actorId, PlayerAction action,
                                           ActionPoolSnapshot next, String idempotencyKey) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("actionId", action.actionId());
        summary.put("playerId", action.playerId());
        summary.put("status", action.status().name());
        if (action.packageId() != null) {
            summary.put("packageId", action.packageId());
        }
        summary.put("actionCount", next.actions().size());
        if (idempotencyKey != null) {
            summary.put("idempotencyKeyHash", Hashes.sha256(idempotencyKey));
        }
        return new DomainEvent(now, eventType, actorRole, actorId,
                action.actionId(), null, EventVisibility.SECRETARY, next, summary);
    }

    private PlayerAction findAction(String actionId) {
        return snapshot.action(actionId).orElseThrow(() -> new ActionPoolException(
                ActionPoolException.NOT_FOUND, "行动不存在: " + actionId));
    }

    private ActionPoolSnapshot replaceAction(PlayerAction replacement) {
        List<PlayerAction> actions = new ArrayList<>(snapshot.actions().size());
        for (PlayerAction action : snapshot.actions()) {
            actions.add(action.actionId().equals(replacement.actionId()) ? replacement : action);
        }
        return new ActionPoolSnapshot(actions, snapshot.idempotentResults());
    }

    private static Map<String, Map<String, String>> copyIdempotentResults(
            ActionPoolSnapshot source) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.idempotentResults().forEach((playerId, results) ->
                copy.put(playerId, new LinkedHashMap<>(results)));
        return copy;
    }

    private static Map<String, String> withResult(Map<String, String> existing,
                                                   String key, String actionId) {
        Map<String, String> result = existing == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        result.put(key, actionId);
        return result;
    }

    private static void requirePlayer(Actor actor) {
        if (actor == null || actor.role() != ActorRole.PLAYER) {
            throw new ActionPoolException(ActionPoolException.FORBIDDEN,
                    "只有 PLAYER 可以提交或撤回行动");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ActionPoolException(ActionPoolException.INVALID_ARGUMENTS,
                    name + " 不能为空白");
        }
    }
}
