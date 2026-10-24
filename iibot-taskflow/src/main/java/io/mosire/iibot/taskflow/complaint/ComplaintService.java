package io.mosire.iibot.taskflow.complaint;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家投诉的线程安全领域服务。
 *
 * <p>状态机：{@code SUBMITTED → RESPONDED}。投诉提交不依赖回合/世界状态，任何已认证玩家
 * 任何时候都可以提交；投诉归属固定为 {@code player.actorId()}。只有
 * DIRECTOR/GM/SUPERVISOR 可以回应。秘书只能查询自己的投诉，GM 查询方法本身不做 actor
 * 校验，由应用层只在 GM 工具面调用。</p>
 *
 * <p><b>原子性</b>：所有写命令先在旧快照上构造完整新快照，调用
 * {@link ComplaintStore#save} 成功后才替换内存状态。校验失败、权限失败、状态/版本冲突或
 * store 失败都保持内存与存储不变；失败不缓存幂等回执。成功命令的幂等回执随快照持久化，
 * 重复键返回首次成功回执且不重复改状态。</p>
 */
public final class ComplaintService {

    public static final String OK = "OK";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";
    public static final String COMPLAINT_NOT_FOUND = "COMPLAINT_NOT_FOUND";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String DELIVERY_STATE_CONFLICT = "DELIVERY_STATE_CONFLICT";
    public static final String STORE_UNAVAILABLE = "STORE_UNAVAILABLE";

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static final int MAX_RESPONSE_BYTES = 16 * 1024;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private static final String DELIVERY_ACTOR = "system:complaint-delivery";

    private static final Comparator<Complaint> COMPLAINT_ORDER =
            Comparator.comparing(Complaint::createdAt).thenComparing(Complaint::complaintId);

    private final ComplaintStore store;
    private final Clock clock;
    private ComplaintSnapshot state;

    public ComplaintService(ComplaintStore store) {
        this(store, Clock.systemUTC());
    }

    public ComplaintService(ComplaintStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        ComplaintSnapshot loaded = store.load();
        if (loaded == null) {
            throw new IllegalStateException("ComplaintStore.load() 不能返回 null");
        }
        this.state = loaded;
    }

    /**
     * 提交当前玩家自己的投诉。玩家身份只来自宿主已认证 actor；不依赖任何回合状态。
     */
    public synchronized ComplaintCommandResult submit(Actor player, String title, String body,
                                                       String idempotencyKey) {
        ComplaintCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        ComplaintCommandResult duplicate = state.idempotentResults().get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (player == null || player.role() != ActorRole.PLAYER) {
            return failure(FORBIDDEN, "只有玩家可以提交投诉", null, 0);
        }
        if (title == null || title.isBlank()) {
            return failure(INVALID_ARGUMENT, "title 不能为空白", null, 0);
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return failure(INVALID_ARGUMENT, "title 超过长度限制", null, 0);
        }
        String bodyFailure = validateBody(body, "body");
        if (bodyFailure != null) {
            return failure(INVALID_ARGUMENT, bodyFailure, null, 0);
        }

        Instant now = clock.instant();
        String complaintId = UUID.randomUUID().toString();
        Complaint complaint = new Complaint(complaintId, player.actorId(), title, body,
                ComplaintStatus.SUBMITTED, 1L, now, now, null, null, null, null);
        ComplaintCommandResult result = ComplaintCommandResult.success(complaintId,
                complaint.revision());
        ComplaintSnapshot next = state
                .withComplaint(complaint)
                .withIdempotentResult(idempotencyKey, result)
                .withAuditEntry(new ComplaintAuditEntry(ComplaintAuditAction.SUBMIT,
                        complaintId, player.actorId(), player.actorId(), complaint.revision(),
                        Hashes.sha256(body), now));
        DomainEvent event = new DomainEvent(now, DomainEventTypes.COMPLAINT_SUBMITTED,
                player.role(), player.actorId(), complaintId, null,
                EventVisibility.SECRETARY, next, Map.of(
                "complaintId", complaint.complaintId(),
                "playerId", complaint.playerId(),
                "status", complaint.status().name(),
                "revision", complaint.revision(),
                "idempotencyKeyHash", Hashes.sha256(idempotencyKey),
                "complaintCount", next.size()));
        return commit(next, result, event);
    }

    /** 只返回指定秘书所属玩家的投诉；空白 playerId 返回空列表。 */
    public synchronized List<Complaint> listForSecretary(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return List.of();
        }
        return state.complaints().stream()
                .filter(complaint -> complaint.playerId().equals(playerId))
                .sorted(COMPLAINT_ORDER)
                .toList();
    }

    /** 读取指定玩家的投诉；他人的投诉与不存在统一返回 empty。 */
    public synchronized Optional<Complaint> getForSecretary(String playerId, String complaintId) {
        if (playerId == null || playerId.isBlank()
                || complaintId == null || complaintId.isBlank()) {
            return Optional.empty();
        }
        return state.complaints().stream()
                .filter(complaint -> complaint.complaintId().equals(complaintId))
                .filter(complaint -> complaint.playerId().equals(playerId))
                .findFirst();
    }

    /** GM 侧列表；status 为 null 时返回全部。此方法不在领域层校验 actor。 */
    public synchronized List<Complaint> listForGm(ComplaintStatus status) {
        return state.complaints().stream()
                .filter(complaint -> status == null || complaint.status() == status)
                .sorted(COMPLAINT_ORDER)
                .toList();
    }

    /** GM 侧按 ID 读取全部投诉。此方法不在领域层校验 actor。 */
    public synchronized Optional<Complaint> getForGm(String complaintId) {
        return state.findComplaint(complaintId);
    }

    /**
     * GM 回应投诉：{@code SUBMITTED → RESPONDED}。
     *
     * <p>只接受 DIRECTOR/GM/SUPERVISOR；expectedRevision 必须等于当前单条投诉版本。
     * 成功后投诉 revision 加一、回应正文落库、投递状态置为 PENDING。失败零副作用。</p>
     */
    public synchronized ComplaintCommandResult respond(Actor gm, String complaintId,
                                                        long expectedRevision,
                                                        String responseBody,
                                                        String idempotencyKey) {
        ComplaintCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        ComplaintCommandResult duplicate = state.idempotentResults().get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以回应投诉",
                    complaintId, 0);
        }
        if (complaintId == null || complaintId.isBlank()) {
            return failure(INVALID_ARGUMENT, "complaintId 不能为空", null, 0);
        }
        String responseFailure = validateBody(responseBody, "responseBody");
        if (responseFailure != null) {
            return failure(INVALID_ARGUMENT, responseFailure, complaintId, 0);
        }
        Complaint complaint = state.findComplaint(complaintId).orElse(null);
        if (complaint == null) {
            return failure(COMPLAINT_NOT_FOUND, "投诉不存在", complaintId, 0);
        }
        if (expectedRevision != complaint.revision()) {
            return failure(REVISION_CONFLICT, "投诉版本已变化，请刷新后重试",
                    complaintId, complaint.revision());
        }
        if (complaint.status() != ComplaintStatus.SUBMITTED) {
            return failure(INVALID_STATUS, "只有待回应投诉可以回应",
                    complaintId, complaint.revision());
        }

        Instant now = clock.instant();
        Complaint updated = new Complaint(complaint.complaintId(), complaint.playerId(),
                complaint.title(), complaint.body(), ComplaintStatus.RESPONDED,
                complaint.revision() + 1, complaint.createdAt(), now, gm.actorId(),
                responseBody, now, ComplaintDeliveryState.PENDING);
        ComplaintCommandResult result = ComplaintCommandResult.success(complaintId,
                updated.revision());
        ComplaintSnapshot next = state
                .withComplaint(updated)
                .withIdempotentResult(idempotencyKey, result)
                .withAuditEntry(new ComplaintAuditEntry(ComplaintAuditAction.RESPOND,
                        complaintId, complaint.playerId(), gm.actorId(), updated.revision(),
                        Hashes.sha256(responseBody), now));
        DomainEvent event = new DomainEvent(now, DomainEventTypes.COMPLAINT_RESPONDED,
                gm.role(), gm.actorId(), complaintId, null,
                EventVisibility.SECRETARY, next, Map.of(
                "complaintId", updated.complaintId(),
                "playerId", updated.playerId(),
                "status", updated.status().name(),
                "revision", updated.revision(),
                "idempotencyKeyHash", Hashes.sha256(idempotencyKey),
                "deliveryState", updated.deliveryState().name(),
                "complaintCount", next.size()));
        return commit(next, result, event);
    }

    /**
     * 记录 GM 回应投递结果。仅 RESPONDED 投诉可调用；UNKNOWN 不得重置为 PENDING。
     * SENT/FAILED/UNKNOWN 是合法投递结果；重复标记同一状态是安全 no-op。
     */
    public synchronized ComplaintCommandResult markDelivered(String complaintId,
                                                              ComplaintDeliveryState deliveryState) {
        if (complaintId == null || complaintId.isBlank()) {
            return failure(INVALID_ARGUMENT, "complaintId 不能为空", null, 0);
        }
        if (deliveryState == null) {
            return failure(INVALID_ARGUMENT, "deliveryState 不能为空", complaintId, 0);
        }
        Complaint complaint = state.findComplaint(complaintId).orElse(null);
        if (complaint == null) {
            return failure(COMPLAINT_NOT_FOUND, "投诉不存在", complaintId, 0);
        }
        if (complaint.status() != ComplaintStatus.RESPONDED) {
            return failure(INVALID_STATUS, "只有已回应投诉可以记录送达状态",
                    complaintId, complaint.revision());
        }
        if (complaint.deliveryState() == deliveryState) {
            return ComplaintCommandResult.success(complaintId, complaint.revision());
        }
        if (deliveryState == ComplaintDeliveryState.PENDING) {
            // PENDING 是回应写入后的内部初始态；任何已落地的投递结果都不能被重置。
            return failure(DELIVERY_STATE_CONFLICT, "投递状态不能重置为 PENDING",
                    complaintId, complaint.revision());
        }

        Instant now = clock.instant();
        Complaint updated = new Complaint(complaint.complaintId(), complaint.playerId(),
                complaint.title(), complaint.body(), complaint.status(),
                complaint.revision() + 1, complaint.createdAt(), now,
                complaint.respondedBy(), complaint.responseBody(), complaint.respondedAt(),
                deliveryState);
        ComplaintCommandResult result = ComplaintCommandResult.success(complaintId,
                updated.revision());
        ComplaintSnapshot next = state
                .withComplaint(updated)
                .withAuditEntry(new ComplaintAuditEntry(ComplaintAuditAction.DELIVERY,
                        complaintId, complaint.playerId(), DELIVERY_ACTOR,
                        updated.revision(), Hashes.sha256("delivery:" + deliveryState.name()),
                        now));
        DomainEvent event = new DomainEvent(now, DomainEventTypes.COMPLAINT_DELIVERY_CHANGED,
                ActorRole.SUPERVISOR, DELIVERY_ACTOR, complaintId, null,
                EventVisibility.SECRETARY, next, Map.of(
                "complaintId", updated.complaintId(),
                "playerId", updated.playerId(),
                "status", updated.status().name(),
                "revision", updated.revision(),
                "deliveryState", updated.deliveryState().name(),
                "complaintCount", next.size()));
        return commit(next, result, event);
    }

    /** 只读审计摘要；不包含投诉标题、正文或回应正文。 */
    public synchronized List<ComplaintAuditEntry> auditLog() {
        return state.auditLog();
    }

    /** 最近一次成功持久化的快照；供宿主只读观察/测试。 */
    public synchronized ComplaintSnapshot snapshot() {
        return state;
    }

    private ComplaintCommandResult commit(ComplaintSnapshot next,
                                           ComplaintCommandResult success,
                                           DomainEvent event) {
        try {
            store.save(next, event);
            state = next;
            return success;
        } catch (RuntimeException failure) {
            return failure(STORE_UNAVAILABLE, "投诉存储暂不可用，操作未生效",
                    success.complaintId(), success.revision());
        }
    }

    private static boolean isGmSide(Actor actor) {
        if (actor == null) {
            return false;
        }
        return actor.role() == ActorRole.DIRECTOR
                || actor.role() == ActorRole.GM
                || actor.role() == ActorRole.SUPERVISOR;
    }

    private static ComplaintCommandResult validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return failure(INVALID_ARGUMENT, "idempotencyKey 不能为空白", null, 0);
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return failure(INVALID_ARGUMENT, "idempotencyKey 超过长度限制", null, 0);
        }
        return null;
    }

    private static String validateBody(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return fieldName + " 不能为空白";
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return fieldName + " 超过长度限制";
        }
        return null;
    }

    private static ComplaintCommandResult failure(String code, String message,
                                                   String complaintId, long revision) {
        return ComplaintCommandResult.failure(code, message, complaintId, revision);
    }
}
