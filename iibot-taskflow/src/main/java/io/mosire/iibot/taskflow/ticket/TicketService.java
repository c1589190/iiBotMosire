package io.mosire.iibot.taskflow.ticket;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 统一工单领域服务。
 *
 * <p>所有写命令先构造完整 next {@link TicketSnapshot}，调用 {@link TicketStore#save}
 * 成功后才替换内存；失败零变更且不缓存幂等回执。事件 content 是完整快照，summary 只含
 * 索引字段。工单正文不进入日志或摘要。</p>
 */
public final class TicketService {

    public static final String OK = "OK";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";
    public static final String TICKET_NOT_FOUND = "TICKET_NOT_FOUND";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String STORE_UNAVAILABLE = "TICKET_STORE_UNAVAILABLE";
    /** 兼容按完整稳定码命名的调用方。 */
    public static final String TICKET_STORE_UNAVAILABLE = STORE_UNAVAILABLE;
    public static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";

    public static final int MAX_TITLE_CHARS = 200;
    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static final int MAX_REASON_BYTES = 2 * 1024;
    public static final int MAX_IDEMPOTENCY_KEY_CHARS = 200;
    public static final int MAX_BATCH_ITEMS = 100;
    public static final int MAX_GM_LIST_LIMIT = 500;

    private static final Comparator<Ticket> TICKET_ORDER =
            Comparator.comparing(Ticket::createdAt).thenComparing(Ticket::ticketId);

    private final TicketStore store;
    private final Clock clock;
    private TicketSnapshot state;

    public TicketService(TicketStore store) {
        this(store, Clock.systemUTC());
    }

    public TicketService(TicketStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        TicketSnapshot loaded = store.load();
        if (loaded == null) {
            throw new IllegalStateException("TicketStore.load() 不能返回 null");
        }
        this.state = loaded;
    }

    public synchronized TicketCommandResult submit(Actor player, TicketType type,
                                                   String title, String body,
                                                   String idempotencyKey) {
        TicketCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        // 鉴权与参数校验必须先于任何幂等回执查询，避免旧 key 绕过 actor 检查。
        if (player == null || player.role() != ActorRole.PLAYER) {
            return failure(FORBIDDEN, "只有玩家可以提交工单", null, 0);
        }
        if (type == null || type == TicketType.GM_NOTE) {
            return failure(INVALID_ARGUMENT, "秘书仅可提交 SETTING_QUERY/ACTION_DRAFT/COMPLAINT",
                    null, 0);
        }
        String invalid = validateCommon(title, body);
        if (invalid != null) {
            return failure(INVALID_ARGUMENT, invalid, null, 0);
        }
        String fingerprint = submitFingerprint(player, type, title, body);
        TicketCommandResult duplicate = duplicateOrConflict(idempotencyKey, fingerprint);
        if (duplicate != null) {
            return duplicate;
        }
        Instant now = clock.instant();
        String ticketId = UUID.randomUUID().toString();
        Ticket ticket = new Ticket(ticketId, type, player.actorId(), player.actorId(),
                title, body, TicketStatus.OPEN, 1L, now, now,
                null, List.of(), null, null);
        TicketCommandResult result = TicketCommandResult.success(ticketId, 1L);
        TicketSnapshot next = state.withTickets(List.of(ticket), idempotencyKey,
                fingerprint, result);
        return commit(next, result, event(TICKET_EVENT_CREATED, player, ticketId, next,
                summary(ticket, next, idempotencyKey), now));
    }

    public synchronized TicketCommandResult createGmNote(Actor gm, String title, String body,
                                                          String idempotencyKey) {
        TicketCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以创建 GM_NOTE",
                    null, 0);
        }
        String invalid = validateCommon(title, body);
        if (invalid != null) {
            return failure(INVALID_ARGUMENT, invalid, null, 0);
        }
        String fingerprint = createGmNoteFingerprint(gm, title, body);
        TicketCommandResult duplicate = duplicateOrConflict(idempotencyKey, fingerprint);
        if (duplicate != null) {
            return duplicate;
        }
        Instant now = clock.instant();
        String ticketId = UUID.randomUUID().toString();
        Ticket ticket = new Ticket(ticketId, TicketType.GM_NOTE, null, gm.actorId(),
                title, body, TicketStatus.OPEN, 1L, now, now,
                null, List.of(), null, null);
        TicketCommandResult result = TicketCommandResult.success(ticketId, 1L);
        TicketSnapshot next = state.withTickets(List.of(ticket), idempotencyKey,
                fingerprint, result);
        return commit(next, result, event(TICKET_EVENT_CREATED, gm, ticketId, next,
                summary(ticket, next, idempotencyKey), now));
    }

    public synchronized List<Ticket> listForSecretary(String playerId, TicketType type,
                                                       TicketStatus status) {
        if (playerId == null || playerId.isBlank()) {
            return List.of();
        }
        return state.tickets().stream()
                .filter(ticket -> playerId.equals(ticket.playerId()))
                .filter(ticket -> type == null || ticket.type() == type)
                .filter(ticket -> status == null || ticket.status() == status)
                .sorted(TICKET_ORDER)
                .toList();
    }

    public synchronized List<Ticket> listForSecretary(String playerId) {
        return listForSecretary(playerId, null, null);
    }

    public synchronized Optional<Ticket> getForSecretary(String playerId, String ticketId) {
        if (playerId == null || playerId.isBlank() || ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return state.find(ticketId)
                .filter(ticket -> playerId.equals(ticket.playerId()));
    }

    public synchronized List<Ticket> listForGm(TicketType type, TicketStatus status,
                                                int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正");
        }
        int bounded = Math.min(limit, MAX_GM_LIST_LIMIT);
        return state.tickets().stream()
                .filter(ticket -> type == null || ticket.type() == type)
                .filter(ticket -> status == null || ticket.status() == status)
                .sorted(TICKET_ORDER)
                .limit(bounded)
                .toList();
    }

    public synchronized List<Ticket> listForGm() {
        return listForGm(null, null, MAX_GM_LIST_LIMIT);
    }

    public synchronized Optional<Ticket> getForGm(String ticketId) {
        return state.find(ticketId);
    }

    public synchronized TicketCommandResult claim(Actor gm, String ticketId,
                                                    long expectedRevision,
                                                    String idempotencyKey) {
        TicketCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以 claim", ticketId, 0);
        }
        if (ticketId == null || ticketId.isBlank()) {
            return failure(INVALID_ARGUMENT, "ticketId 不能为空", null, 0);
        }
        if (expectedRevision < 0) {
            return failure(INVALID_ARGUMENT, "expectedRevision 不能为负", ticketId, 0);
        }
        String fingerprint = claimFingerprint(gm, ticketId, expectedRevision);
        TicketCommandResult duplicate = duplicateOrConflict(idempotencyKey, fingerprint);
        if (duplicate != null) {
            return duplicate;
        }
        Ticket ticket = findRequired(ticketId);
        if (ticket == null) {
            return failure(TICKET_NOT_FOUND, "工单不存在", ticketId, 0);
        }
        if (ticket.revision() != expectedRevision) {
            return failure(REVISION_CONFLICT, "工单版本已变化，请刷新后重试",
                    ticketId, ticket.revision());
        }
        if (ticket.status() != TicketStatus.OPEN) {
            return failure(INVALID_STATUS, "只有 OPEN 工单可以 claim",
                    ticketId, ticket.revision());
        }
        Instant now = clock.instant();
        Ticket updated = new Ticket(ticket.ticketId(), ticket.type(), ticket.playerId(),
                ticket.authorActorId(), ticket.title(), ticket.body(), TicketStatus.CLAIMED,
                ticket.revision() + 1, ticket.createdAt(), now, gm.actorId(),
                ticket.replies(), null, null);
        TicketCommandResult result = TicketCommandResult.success(ticketId, updated.revision());
        TicketSnapshot next = state.withTickets(List.of(updated), idempotencyKey,
                fingerprint, result);
        return commit(next, result, event(DomainEventTypes.TICKET_CLAIMED, gm, ticketId, next,
                summary(updated, next, idempotencyKey), now));
    }

    public synchronized TicketCommandResult reply(Actor gm, String ticketId,
                                                    long expectedRevision, String replyBody,
                                                    String idempotencyKey) {
        TicketCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以回复", ticketId, 0);
        }
        if (ticketId == null || ticketId.isBlank()) {
            return failure(INVALID_ARGUMENT, "ticketId 不能为空", null, 0);
        }
        if (expectedRevision < 0) {
            return failure(INVALID_ARGUMENT, "expectedRevision 不能为负", ticketId, 0);
        }
        String bodyFailure = validateBody(replyBody, "replyBody");
        if (bodyFailure != null) {
            return failure(INVALID_ARGUMENT, bodyFailure, ticketId, 0);
        }
        String fingerprint = replyFingerprint(gm, ticketId, expectedRevision, replyBody);
        TicketCommandResult duplicate = duplicateOrConflict(idempotencyKey, fingerprint);
        if (duplicate != null) {
            return duplicate;
        }
        Ticket ticket = findRequired(ticketId);
        if (ticket == null) {
            return failure(TICKET_NOT_FOUND, "工单不存在", ticketId, 0);
        }
        if (ticket.revision() != expectedRevision) {
            return failure(REVISION_CONFLICT, "工单版本已变化，请刷新后重试",
                    ticketId, ticket.revision());
        }
        if (ticket.status() != TicketStatus.OPEN
                && ticket.status() != TicketStatus.CLAIMED) {
            return failure(INVALID_STATUS, "只有 OPEN/CLAIMED 工单可以回复",
                    ticketId, ticket.revision());
        }
        Instant now = clock.instant();
        List<TicketReply> replies = new ArrayList<>(ticket.replies());
        replies.add(new TicketReply(ticketId, UUID.randomUUID().toString(),
                gm.actorId(), replyBody, now));
        Ticket updated = new Ticket(ticket.ticketId(), ticket.type(), ticket.playerId(),
                ticket.authorActorId(), ticket.title(), ticket.body(), TicketStatus.RESOLVED,
                ticket.revision() + 1, ticket.createdAt(), now, ticket.claimedBy(),
                replies, null, null);
        TicketCommandResult result = TicketCommandResult.success(ticketId, updated.revision());
        TicketSnapshot next = state.withTickets(List.of(updated), idempotencyKey,
                fingerprint, result);
        return commit(next, result, event(DomainEventTypes.TICKET_REPLIED, gm, ticketId, next,
                summary(updated, next, idempotencyKey), now));
    }

    /**
     * 批量回复：所有正文/版本/状态先在内存副本中校验，任一失败整批零变更；成功时只写一份
     * 新快照和一个 TICKET_BATCH_REPLIED 事件。
     */
    public synchronized TicketCommandResult batchReply(Actor gm, List<?> items,
                                                         String idempotencyKey) {
        TicketCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以批量回复", null, 0);
        }
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_ITEMS) {
            return failure(INVALID_ARGUMENT, "批量回复条目数量非法", null, 0);
        }
        // 先把所有条目做语法/正文/重复 ID 校验，再查幂等回执；随后再做状态/版本校验。
        List<ParsedBatchItem> parsedItems = new ArrayList<>(items.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Object raw : items) {
            ParsedBatchItem item = parseBatchItem(raw);
            if (item == null) {
                return failure(INVALID_ARGUMENT, "批量回复条目类型不支持", null, 0);
            }
            if (!seen.add(item.ticketId())) {
                return failure(INVALID_ARGUMENT, "批量回复不能重复 ticketId",
                        item.ticketId(), 0);
            }
            if (item.expectedRevision() < 0) {
                return failure(INVALID_ARGUMENT, "expectedRevision 不能为负",
                        item.ticketId(), 0);
            }
            String bodyFailure = validateBody(item.replyBody(), "replyBody");
            if (bodyFailure != null) {
                return failure(INVALID_ARGUMENT, bodyFailure, item.ticketId(), 0);
            }
            parsedItems.add(item);
        }
        String fingerprint = batchReplyFingerprint(gm, parsedItems);
        TicketCommandResult duplicate = duplicateOrConflict(idempotencyKey, fingerprint);
        if (duplicate != null) {
            return duplicate;
        }

        Instant now = clock.instant();
        List<Ticket> replacements = new ArrayList<>(parsedItems.size());
        for (ParsedBatchItem item : parsedItems) {
            Ticket ticket = findRequired(item.ticketId());
            if (ticket == null) {
                return failure(TICKET_NOT_FOUND, "工单不存在", item.ticketId(), 0);
            }
            if (ticket.revision() != item.expectedRevision()) {
                return failure(REVISION_CONFLICT, "工单版本已变化，请刷新后重试",
                        item.ticketId(), ticket.revision());
            }
            if (ticket.status() != TicketStatus.OPEN
                    && ticket.status() != TicketStatus.CLAIMED) {
                return failure(INVALID_STATUS, "只有 OPEN/CLAIMED 工单可以回复",
                        item.ticketId(), ticket.revision());
            }
            List<TicketReply> replies = new ArrayList<>(ticket.replies());
            replies.add(new TicketReply(ticket.ticketId(), UUID.randomUUID().toString(),
                    gm.actorId(), item.replyBody(), now));
            replacements.add(new Ticket(ticket.ticketId(), ticket.type(), ticket.playerId(),
                    ticket.authorActorId(), ticket.title(), ticket.body(),
                    TicketStatus.RESOLVED, ticket.revision() + 1, ticket.createdAt(), now,
                    ticket.claimedBy(), replies, null, null));
        }
        long maxRevision = replacements.stream().mapToLong(Ticket::revision).max().orElse(0);
        TicketCommandResult result = TicketCommandResult.success(null, maxRevision);
        TicketSnapshot next = state.withTickets(replacements, idempotencyKey,
                fingerprint, result);
        String batchId = "batch-" + UUID.randomUUID();
        List<String> batchTicketIds = new ArrayList<>(seen);
        batchTicketIds.sort(String::compareTo);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ticketId", batchId);
        summary.put("ticketIds", List.copyOf(batchTicketIds));
        summary.put("type", "BATCH");
        summary.put("status", TicketStatus.RESOLVED.name());
        summary.put("playerId", null);
        summary.put("revision", maxRevision);
        summary.put("count", next.size());
        summary.put("idempotencyKeyHash", Hashes.sha256(idempotencyKey));
        // correlationId 必须短且稳定；不得拼接最多 100 个 ticketId。
        return commit(next, result, event(DomainEventTypes.TICKET_BATCH_REPLIED, gm,
                batchId, next, summary, now));
    }

    public synchronized TicketCommandResult close(Actor gm, String ticketId,
                                                    long expectedRevision, String reason,
                                                    String idempotencyKey) {
        TicketCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以关闭", ticketId, 0);
        }
        if (ticketId == null || ticketId.isBlank()) {
            return failure(INVALID_ARGUMENT, "ticketId 不能为空", null, 0);
        }
        if (expectedRevision < 0) {
            return failure(INVALID_ARGUMENT, "expectedRevision 不能为负", ticketId, 0);
        }
        String reasonFailure = validateReason(reason);
        if (reasonFailure != null) {
            return failure(INVALID_ARGUMENT, reasonFailure, ticketId, 0);
        }
        String fingerprint = closeFingerprint(gm, ticketId, expectedRevision, reason);
        TicketCommandResult duplicate = duplicateOrConflict(idempotencyKey, fingerprint);
        if (duplicate != null) {
            return duplicate;
        }
        Ticket ticket = findRequired(ticketId);
        if (ticket == null) {
            return failure(TICKET_NOT_FOUND, "工单不存在", ticketId, 0);
        }
        if (ticket.revision() != expectedRevision) {
            return failure(REVISION_CONFLICT, "工单版本已变化，请刷新后重试",
                    ticketId, ticket.revision());
        }
        if (ticket.status() == TicketStatus.CLOSED) {
            return failure(INVALID_STATUS, "工单已经关闭", ticketId, ticket.revision());
        }
        Instant now = clock.instant();
        Ticket updated = new Ticket(ticket.ticketId(), ticket.type(), ticket.playerId(),
                ticket.authorActorId(), ticket.title(), ticket.body(), TicketStatus.CLOSED,
                ticket.revision() + 1, ticket.createdAt(), now, ticket.claimedBy(),
                ticket.replies(), gm.actorId(), reason);
        TicketCommandResult result = TicketCommandResult.success(ticketId, updated.revision());
        TicketSnapshot next = state.withTickets(List.of(updated), idempotencyKey,
                fingerprint, result);
        return commit(next, result, event(DomainEventTypes.TICKET_CLOSED, gm, ticketId, next,
                summary(updated, next, idempotencyKey), now));
    }

    public synchronized TicketSnapshot snapshot() {
        return state;
    }

    /** 只读审计摘要；不含 title/body/reply 正文。 */
    public synchronized List<Map<String, Object>> audit() {
        List<Map<String, Object>> result = new ArrayList<>(state.size());
        for (Ticket ticket : state.tickets()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ticketId", ticket.ticketId());
            item.put("type", ticket.type().name());
            item.put("playerId", ticket.playerId());
            item.put("authorActorId", ticket.authorActorId());
            item.put("status", ticket.status().name());
            item.put("revision", ticket.revision());
            item.put("createdAt", ticket.createdAt().toString());
            item.put("updatedAt", ticket.updatedAt().toString());
            item.put("claimedBy", ticket.claimedBy());
            item.put("replyCount", ticket.replies().size());
            item.put("closedBy", ticket.closedBy());
            result.add(java.util.Collections.unmodifiableMap(item));
        }
        return List.copyOf(result);
    }

    public synchronized List<Map<String, Object>> auditSummary() {
        return audit();
    }

    private TicketCommandResult commit(TicketSnapshot next, TicketCommandResult success,
                                       DomainEvent event) {
        try {
            store.save(next, event);
            state = next;
            return success;
        } catch (RuntimeException failure) {
            return failure(STORE_UNAVAILABLE, "工单存储暂不可用，操作未生效",
                    success.ticketId(), success.revision());
        }
    }

    private DomainEvent event(String eventType, Actor actor, String correlationId,
                              TicketSnapshot next, Map<String, Object> summary, Instant now) {
        return new DomainEvent(now, eventType, actor.role(), actor.actorId(),
                correlationId, null, EventVisibility.GM, next, summary);
    }

    private static Map<String, Object> summary(Ticket ticket, TicketSnapshot next,
                                                String idempotencyKey) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ticketId", ticket.ticketId());
        summary.put("type", ticket.type().name());
        summary.put("status", ticket.status().name());
        summary.put("playerId", ticket.playerId());
        summary.put("revision", ticket.revision());
        summary.put("count", next.size());
        summary.put("idempotencyKeyHash", Hashes.sha256(idempotencyKey));
        return summary;
    }

    private static ParsedBatchItem parseBatchItem(Object raw) {
        if (raw instanceof io.mosire.iibot.taskflow.ticket.BatchReplyItem item) {
            return new ParsedBatchItem(item.ticketId(), item.expectedRevision(),
                    item.replyBody());
        }
        if (raw instanceof BatchReplyItem item) {
            return new ParsedBatchItem(item.ticketId(), item.expectedRevision(),
                    item.replyBody());
        }
        return null;
    }

    private Ticket findRequired(String ticketId) {
        return state.find(ticketId).orElse(null);
    }

    private static boolean isGmSide(Actor actor) {
        return actor != null && (actor.role() == ActorRole.DIRECTOR
                || actor.role() == ActorRole.GM
                || actor.role() == ActorRole.SUPERVISOR);
    }

    /**
     * 同一 key 只有相同请求 fingerprint 才能命中首次成功；缺少 fingerprint 的旧快照
     * 或不同请求一律 fail-closed，避免仅凭 key 把不同命令重放成首次成功。
     */
    private TicketCommandResult duplicateOrConflict(String key, String fingerprint) {
        boolean hasResult = state.idempotentResults().containsKey(key);
        String storedFingerprint = state.idempotencyFingerprints().get(key);
        if (!hasResult && storedFingerprint == null) {
            return null;
        }
        if (!hasResult || storedFingerprint == null
                || !storedFingerprint.equals(fingerprint)) {
            return failure(IDEMPOTENCY_KEY_CONFLICT,
                    "同一幂等键已被不同请求或缺失 fingerprint 的旧回执占用", null, 0);
        }
        return state.idempotentResults().get(key);
    }

    private static String submitFingerprint(Actor actor, TicketType type,
                                            String title, String body) {
        return requestFingerprint("SUBMIT", actor, "", 0L, List.of(
                type == null ? "" : type.name(), shaOrEmpty(title), shaOrEmpty(body)));
    }

    private static String createGmNoteFingerprint(Actor actor, String title, String body) {
        return requestFingerprint("CREATE_GM_NOTE", actor, "", 0L,
                List.of(shaOrEmpty(title), shaOrEmpty(body)));
    }

    private static String claimFingerprint(Actor actor, String ticketId,
                                           long expectedRevision) {
        return requestFingerprint("CLAIM", actor, ticketId, expectedRevision, List.of());
    }

    private static String replyFingerprint(Actor actor, String ticketId,
                                           long expectedRevision, String replyBody) {
        return requestFingerprint("REPLY", actor, ticketId, expectedRevision,
                List.of(shaOrEmpty(replyBody)));
    }

    private static String batchReplyFingerprint(Actor actor,
                                                List<ParsedBatchItem> items) {
        List<ParsedBatchItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparing(ParsedBatchItem::ticketId)
                .thenComparingLong(ParsedBatchItem::expectedRevision));
        List<String> fields = new ArrayList<>(ordered.size() * 3 + 1);
        fields.add("count=" + ordered.size());
        for (ParsedBatchItem item : ordered) {
            fields.add(item.ticketId());
            fields.add(Long.toString(item.expectedRevision()));
            fields.add(shaOrEmpty(item.replyBody()));
        }
        return requestFingerprint("BATCH_REPLY", actor, "", 0L, fields);
    }

    private static String closeFingerprint(Actor actor, String ticketId,
                                           long expectedRevision, String reason) {
        return requestFingerprint("CLOSE", actor, ticketId, expectedRevision,
                List.of(shaOrEmpty(reason)));
    }

    private static String requestFingerprint(String command, Actor actor, String ticketId,
                                             long expectedRevision, List<String> fields) {
        StringBuilder canonical = new StringBuilder();
        appendFingerprintField(canonical, command);
        appendFingerprintField(canonical, actor == null ? "" : actor.role().name());
        appendFingerprintField(canonical, actor == null ? "" : actor.actorId());
        appendFingerprintField(canonical, ticketId);
        appendFingerprintField(canonical, Long.toString(expectedRevision));
        for (String field : fields) {
            appendFingerprintField(canonical, field);
        }
        return Hashes.sha256(canonical.toString());
    }

    private static void appendFingerprintField(StringBuilder canonical, String value) {
        String safe = value == null ? "" : value;
        canonical.append(safe.length()).append(':').append(safe).append('|');
    }

    private static String shaOrEmpty(String value) {
        return Hashes.sha256(value == null ? "" : value);
    }

    private static TicketCommandResult validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return failure(INVALID_ARGUMENT, "idempotencyKey 不能为空白", null, 0);
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_CHARS) {
            return failure(INVALID_ARGUMENT, "idempotencyKey 超过长度限制", null, 0);
        }
        return null;
    }

    private static String validateCommon(String title, String body) {
        if (title == null || title.isBlank()) {
            return "title 不能为空白";
        }
        if (title.length() > MAX_TITLE_CHARS) {
            return "title 超过长度限制";
        }
        return validateBody(body, "body");
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

    private static String validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "closeReason 不能为空白";
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length > MAX_REASON_BYTES) {
            return "closeReason 超过长度限制";
        }
        return null;
    }

    private static TicketCommandResult failure(String code, String message,
                                                String ticketId, long revision) {
        return TicketCommandResult.failure(code, message, ticketId, revision);
    }

    /** TICKET_CREATED 的事件类型常量别名，避免业务方法名与常量名混淆。 */
    private static final String TICKET_EVENT_CREATED = DomainEventTypes.TICKET_CREATED;

    private record ParsedBatchItem(String ticketId, long expectedRevision, String replyBody) {
    }

    /** 兼容旧/嵌套调用形状的批量回复输入项。 */
    public record BatchReplyItem(String ticketId, long expectedRevision, String replyBody) {
        public BatchReplyItem {
            if (ticketId == null || ticketId.isBlank()) {
                throw new IllegalArgumentException("ticketId 不能为空");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision 不能为负");
            }
            if (replyBody == null || replyBody.isBlank()) {
                throw new IllegalArgumentException("replyBody 不能为空");
            }
        }
    }
}
