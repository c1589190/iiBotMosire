package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.note.UserNote;
import io.mosire.iibot.bot.note.UserNoteStore;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.bot.summary.MessageSummary;
import io.mosire.iibot.bot.summary.MessageSummaryBuilder;
import io.mosire.iibot.bot.weight.InboxWeight;
import io.mosire.iibot.bot.weight.MessageWeightDecision;
import io.mosire.iibot.bot.weight.SourceWeightPolicy;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.connect.ops.OpsLog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把消息板中尚未进入 Inbox 的入站消息，聚合成一个来源大框。
 *
 * <p>自己发出的 OUT 消息只推进游标，不产生 Inbox 唤醒。一个 thread 的连续消息在调用
 * {@link #flushThread(String)} 时合并；调用方可用外部定时器实现防抖。</p>
 */
public final class InboxDigestService {

    public static final int DEFAULT_MAX_MESSAGES_PER_FLUSH = 100;
    public static final int DEFAULT_CONTEXT_MESSAGES = 6;
    public static final int DEFAULT_CONTEXT_MAX_CHARS = 1200;

    private final MessageBoardStore board;
    private final UserNoteStore notes;
    private final InboxDigestStateStore states;
    private final InboxDigestPort port;
    private final MessageSummaryBuilder summaryBuilder;
    private final int maxMessagesPerFlush;
    private final InboxDigestAuditStore auditStore;
    private final Clock clock;
    private final SourceWeightService weights;

    public InboxDigestService(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestStateStore states,
            InboxDigestPort port) {
        this(board, notes, states, port, new MessageSummaryBuilder(),
                DEFAULT_MAX_MESSAGES_PER_FLUSH, null, Clock.systemUTC());
    }

    public InboxDigestService(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestStateStore states,
            InboxDigestPort port,
            MessageSummaryBuilder summaryBuilder,
            int maxMessagesPerFlush) {
        this(board, notes, states, port, summaryBuilder, maxMessagesPerFlush,
                null, Clock.systemUTC());
    }

    public InboxDigestService(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestStateStore states,
            InboxDigestPort port,
            MessageSummaryBuilder summaryBuilder,
            int maxMessagesPerFlush,
            InboxDigestAuditStore auditStore,
            Clock clock) {
        this(board, notes, states, port, summaryBuilder, maxMessagesPerFlush,
                auditStore, clock, null);
    }

    public InboxDigestService(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestStateStore states,
            InboxDigestPort port,
            MessageSummaryBuilder summaryBuilder,
            int maxMessagesPerFlush,
            InboxDigestAuditStore auditStore,
            Clock clock,
            SourceWeightService weights) {
        this.board = Objects.requireNonNull(board, "board");
        this.notes = Objects.requireNonNull(notes, "notes");
        this.states = Objects.requireNonNull(states, "states");
        this.port = Objects.requireNonNull(port, "port");
        this.summaryBuilder = Objects.requireNonNull(summaryBuilder, "summaryBuilder");
        if (maxMessagesPerFlush < 1 || maxMessagesPerFlush > 1000) {
            throw new IllegalArgumentException("maxMessagesPerFlush 必须在 1..1000");
        }
        this.maxMessagesPerFlush = maxMessagesPerFlush;
        this.auditStore = auditStore;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.weights = weights;
    }

    /** 处理一个 thread 自上次 flush 后的消息；同一来源追加到 active 未读桶。 */
    public synchronized FlushResult flushThread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        Optional<InboxDigestState> state = states.find(threadId);
        long afterSeq = state.map(InboxDigestState::lastSeq).orElse(0L);
        if (state.isPresent() && state.orElseThrow().hasActiveItem()) {
            Optional<InboxDigestItem> activeItem = port.view(state.orElseThrow().itemId());
            if (activeItem.isEmpty()) {
                states.save(InboxDigestState.cursorOnly(threadId, afterSeq));
            } else {
                InboxDigestItem active = activeItem.orElseThrow();
                if (!active.available()) {
                    throw statusConflict(active);
                }
                reconcileActive(threadId, state.orElseThrow(), active);
                state = states.find(threadId);
                afterSeq = state.map(InboxDigestState::lastSeq).orElse(active.toSeq());
                InboxDigest existing = port.view(active.itemId())
                        .filter(InboxDigestItem::available)
                        .map(InboxDigestItem::digest)
                        .orElse(active.digest());
                if (existing != null) {
                    UnreadWindow window = unreadWindow(threadId,
                            Math.max(0L, existing.fromSeq() - 1L), maxMessagesPerFlush);
                    if (window.preview.isEmpty()) {
                        if (window.toSeq() > afterSeq) {
                            states.save(state.orElseThrow().withLastSeq(window.toSeq()));
                        }
                        return new FlushResult(threadId, false, "",
                                existing.fromSeq(), existing.toSeq(),
                                existing.messages().size(), existing.priority());
                    }
                    if (existing.messages().equals(window.preview())
                            && existing.unreadCount() == window.unreadCount()) {
                        if (window.toSeq() > afterSeq) {
                            states.save(state.orElseThrow().withLastSeq(window.toSeq()));
                        }
                        return new FlushResult(threadId, false, "",
                                existing.fromSeq(), existing.toSeq(),
                                existing.messages().size(), existing.priority());
                    }
                    InboxDigest merged = buildDigest(threadId, window,
                            existing.trigger());
                    InboxDigestItem updated;
                    try {
                        updated = port.update(active.itemId(), active.revision(), merged);
                    } catch (InboxDigestConflictException conflict) {
                        // A concurrent ack/update won the revision.  The next
                        // scheduler pass will re-read the item and retry.
                        OpsLog.warn("event=inbox_digest_append_conflict threadId={} "
                                        + "itemId={}", threadId, active.itemId());
                        return new FlushResult(threadId, false, "",
                                existing.fromSeq(), existing.toSeq(),
                                existing.messages().size(), existing.priority());
                    }
                    states.save(new InboxDigestState(threadId, updated.itemId(),
                            updated.revision(), window.toSeq()));
                    recordAudit(threadId, updated, merged, "available", clock.instant());
                    OpsLog.info("event=inbox_digest_appended threadId={} itemId={} "
                                    + "revision={} fromSeq={} toSeq={} messages={} "
                                    + "unreadCount={} priority={} wakeReason={}",
                            threadId, updated.itemId(), updated.revision(),
                            merged.fromSeq(), merged.toSeq(), merged.messages().size(),
                            merged.unreadCount(), merged.priority(), merged.wakeReason());
                    return new FlushResult(threadId, true, updated.itemId(),
                            merged.fromSeq(), merged.toSeq(), merged.messages().size(),
                            merged.priority());
                }
                // Legacy/foreign content cannot be appended safely: retain the
                // old conservative behavior until the operator acknowledges it.
                long frozenSeq = state.map(InboxDigestState::lastSeq).orElse(active.toSeq());
                OpsLog.debug("event=inbox_digest_frozen threadId={} itemId={} seq={}",
                        threadId, active.itemId(), frozenSeq);
                return new FlushResult(threadId, false, "",
                        frozenSeq, frozenSeq, 0, 0);
            }
        }

        UnreadWindow window = unreadWindow(threadId, afterSeq, maxMessagesPerFlush);
        if (window.preview.isEmpty()) {
            if (window.toSeq() > afterSeq) {
                InboxDigestState cursor = state
                        .map(value -> value.withLastSeq(window.toSeq()))
                        .orElseGet(() -> InboxDigestState.cursorOnly(
                                threadId, window.toSeq()));
                states.save(cursor);
            }
            OpsLog.debug("event=inbox_digest_empty threadId={} afterSeq={}",
                    threadId, afterSeq);
            return new FlushResult(threadId, false, "", afterSeq, afterSeq, 0, 0);
        }

        InboxDigest digest = buildDigest(threadId, window, null);
        InboxDigestItem item = port.push(activeDedupeKey(threadId), digest);
        InboxDigestState next = new InboxDigestState(threadId, item.itemId(),
                item.revision(), window.toSeq());
        states.save(next);
        recordAudit(threadId, item, digest, "available", clock.instant());
        OpsLog.info("event=inbox_digest_published threadId={} itemId={} revision={} "
                        + "fromSeq={} toSeq={} messages={} unreadCount={} priority={} "
                        + "wakeReason={}",
                threadId, item.itemId(), item.revision(), digest.fromSeq(), digest.toSeq(),
                digest.messages().size(), digest.unreadCount(), digest.priority(),
                digest.wakeReason());
        if (weights != null && "FIRST_CONTACT".equals(digest.wakeReason())) {
            weights.markBootstrapDelivered(threadId);
        }
        return new FlushResult(threadId, true, item.itemId(), digest.fromSeq(),
                digest.toSeq(), digest.messages().size(), digest.priority());
    }

    /**
     * 旧版本可能在 state.item 和 item 内容之间留下 seq 空洞（例如 state=601、
     * item=606..609）。发现空洞时先用消息板补齐未读桶，避免 ack 跳消息。
     */
    private void reconcileActive(String threadId, InboxDigestState state,
                                  InboxDigestItem active) {
        long stateLast = state.lastSeq();
        if (active.fromSeq() <= 0L || active.fromSeq() <= stateLast + 1L) {
            return;
        }
        UnreadWindow window = unreadWindow(threadId, stateLast, maxMessagesPerFlush);
        if (window.preview().isEmpty()) {
            return;
        }
        InboxTrigger previous = active.digest() == null ? null : active.digest().trigger();
        InboxDigest digest = buildDigest(threadId, window, previous);
        InboxDigestItem updated = port.update(active.itemId(), active.revision(), digest);
        states.save(new InboxDigestState(threadId, updated.itemId(),
                updated.revision(), window.toSeq()));
        recordAudit(threadId, updated, digest, "available", clock.instant());
        OpsLog.info("event=inbox_digest_reconciled threadId={} itemId={} revision={} "
                        + "fromSeq={} toSeq={} unreadCount={}",
                threadId, updated.itemId(), updated.revision(), digest.fromSeq(),
                digest.toSeq(), digest.unreadCount());
    }

    private void recordAudit(String threadId, InboxDigestItem item,
                             InboxDigest digest, String status, Instant now) {
        if (auditStore == null) {
            return;
        }
        try {
            auditStore.upsert(new InboxDigestAudit(
                    threadId, item.itemId(), digest.fromSeq(), digest.toSeq(),
                    digest.summary(), digest.priority(), status, item.revision(),
                    now, now, null));
        } catch (RuntimeException failure) {
            // 审计失败不回滚 Inbox 发布。
            OpsLog.warn("event=inbox_audit_failed threadId={} itemId={} status={} "
                            + "errorType={}", threadId, item.itemId(), status,
                    failure.getClass().getSimpleName());
        }
    }

    private List<MessageSummary> summariesFor(List<ChatMessage> messages) {
        List<MessageSummary> summaries = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.direction() != ChatDirection.IN) {
                continue;
            }
            UserNote note = notes.find(message.source().accountId(), message.senderId())
                    .orElse(null);
            summaries.add(summaryBuilder.build(message, note));
        }
        return List.copyOf(summaries);
    }

    private InboxDigest buildDigest(String threadId, UnreadWindow window,
                                    InboxTrigger previousTrigger) {
        return digestOf(threadId, window.preview(),
                contextBefore(threadId, window.fromSeq()), window.sourceMessages(),
                previousTrigger, window.fromSeq(), window.toSeq(),
                window.unreadCount(), window.hasMore());
    }

    /**
     * Read the current unread window for one source. The preview is the last
     * {@code previewLimit} inbound summaries; the unread range and count cover
     * the complete range. Full message bodies remain in the board.
     */
    private UnreadWindow unreadWindow(String threadId, long afterSeq, int previewLimit) {
        ChatThread thread = board.thread(threadId).orElse(null);
        if (thread == null) {
            return new UnreadWindow(afterSeq, afterSeq, 0, false, List.of(), List.of());
        }
        // item 边界必须落在入站消息上；OUT 只作为 context，不能被 ack 当成未读边界。
        long toSeq = Math.max(afterSeq, board.lastInboundSeqAfter(threadId, afterSeq));
        long count = board.countInboundAfter(threadId, afterSeq);
        if (count <= 0L || toSeq <= afterSeq) {
            return new UnreadWindow(afterSeq, toSeq, 0, false, List.of(), List.of());
        }
        List<ChatMessage> oldest = board.readThread(threadId, afterSeq, 1000);
        long fromSeq = oldest.stream()
                .filter(message -> message.direction() == ChatDirection.IN)
                .mapToLong(ChatMessage::localSeq).findFirst()
                .orElse(afterSeq + 1L);
        List<ChatMessage> recent = board.readThreadBefore(threadId, toSeq + 1L, 1000);
        List<ChatMessage> inbound = recent.stream()
                .filter(message -> message.direction() == ChatDirection.IN)
                .filter(message -> message.localSeq() > afterSeq)
                .toList();
        if (inbound.isEmpty()) {
            inbound = oldest.stream()
                    .filter(message -> message.direction() == ChatDirection.IN)
                    .toList();
        }
        List<MessageSummary> summaries = summariesFor(inbound);
        if (summaries.size() > previewLimit) {
            summaries = new ArrayList<>(summaries.subList(
                    summaries.size() - previewLimit, summaries.size()));
        }
        int unreadCount = (int) Math.min(Integer.MAX_VALUE, count);
        return new UnreadWindow(fromSeq, toSeq, unreadCount,
                count > summaries.size(), summaries, inbound);
    }

    private record UnreadWindow(long fromSeq, long toSeq, int unreadCount,
                                boolean hasMore, List<MessageSummary> preview,
                                List<ChatMessage> sourceMessages) {
    }

    private InboxDigest digestOf(String threadId, List<MessageSummary> summaries,
                                 List<MessageSummary> context,
                                 List<ChatMessage> sourceMessages,
                                 InboxTrigger previousTrigger,
                                 long unreadFromSeq,
                                 long unreadToSeq,
                                 int unreadCount,
                                 boolean hasMoreUnread) {
        Map<String, MessageWeightDecision> decisions = weights == null
                ? Map.of()
                : weights.decisions(sourceMessages.stream()
                        .map(ChatMessage::messageKey).toList());
        MessageWeightDecision strongest = decisions.values().stream()
                .max(java.util.Comparator
                        .comparingInt(MessageWeightDecision::totalScore)
                        .thenComparingLong(decision -> sequenceOf(sourceMessages,
                                decision.messageKey())))
                .orElse(null);
        SourceWeightPolicy policy = strongest == null || weights == null
                ? null : weights.findPolicy(threadId).orElse(null);
        int threshold = strongest == null
                ? summaries.stream().mapToInt(MessageSummary::priority).max().orElse(0)
                : policy == null ? strongest.totalScore() : policy.notifyThreshold();
        int priority = strongest == null
                ? summaries.stream().mapToInt(MessageSummary::priority).max().orElse(0)
                : strongest.bootstrap()
                        ? Math.max(strongest.totalScore(), threshold)
                        : strongest.totalScore();
        InboxWeight weight = strongest == null
                ? InboxWeight.none(priority)
                : new InboxWeight(
                        strongest.sourceWeight(), strongest.signalWeight(),
                        strongest.keywordWeight(), priority, threshold,
                        strongest.matchedSignals(), strongest.matchedKeywords());
        InboxSource source = sourceOf(threadId, sourceMessages);
        InboxTrigger trigger = triggerOf(threadId, strongest, sourceMessages,
                summaries, previousTrigger);
        String wakeReason = strongest == null ? "SOURCE_WEIGHT"
                : strongest.wakeReason().name();
        String summary = trigger == null ? summaries.getLast().summary()
                : trigger.summary();
        return InboxDigest.of(unreadFromSeq, unreadToSeq, summaries, context,
                summary, wakeReason, weight, source, trigger, unreadCount,
                hasMoreUnread);
    }

    private InboxSource sourceOf(String threadId, List<ChatMessage> sourceMessages) {
        ChatThread thread = board.thread(threadId).orElse(null);
        if (thread != null) {
            return new InboxSource(threadId, thread.source().chatType().wire(),
                    thread.source().targetId(), thread.displayName());
        }
        if (sourceMessages.isEmpty()) {
            return new InboxSource(threadId, "unknown", "unknown", "");
        }
        ChatMessage first = sourceMessages.getFirst();
        return new InboxSource(threadId, first.source().chatType().wire(),
                first.source().targetId(), "");
    }

    private InboxTrigger triggerOf(String threadId,
                                   MessageWeightDecision strongest,
                                   List<ChatMessage> sourceMessages,
                                   List<MessageSummary> summaries,
                                   InboxTrigger previousTrigger) {
        ChatMessage message = null;
        String reason = strongest == null ? "SOURCE_WEIGHT"
                : strongest.wakeReason().name();
        if (strongest != null) {
            message = sourceMessages.stream()
                    .filter(value -> value.messageKey().equals(strongest.messageKey()))
                    .findFirst().orElse(null);
        }
        if (message == null) {
            message = sourceMessages.stream()
                    .filter(value -> value.direction() == ChatDirection.IN)
                    .reduce((first, second) -> second).orElse(null);
        }
        if (message == null) {
            return previousTrigger;
        }
        ChatMessage triggerMessage = message;
        MessageSummary summary = summaries.stream()
                .filter(value -> value.localSeq() == triggerMessage.localSeq())
                .findFirst()
                .orElseGet(() -> summaryBuilder.build(triggerMessage,
                        notes.find(triggerMessage.source().accountId(),
                                triggerMessage.senderId()).orElse(null)));
        return new InboxTrigger(triggerMessage.messageId(), triggerMessage.localSeq(),
                triggerMessage.senderId(), triggerMessage.senderName(),
                triggerMessage.replyToMessageId(), summary.summary(), reason);
    }

    private static long sequenceOf(List<ChatMessage> messages, String messageKey) {
        return messages.stream()
                .filter(message -> message.messageKey().equals(messageKey))
                .mapToLong(ChatMessage::localSeq)
                .findFirst().orElse(0L);
    }

    private List<MessageSummary> contextBefore(String threadId, long beforeSeq) {
        List<ChatMessage> previous = board.readThreadBefore(
                threadId, beforeSeq, DEFAULT_CONTEXT_MESSAGES);
        List<MessageSummary> summaries = new ArrayList<>();
        for (ChatMessage message : previous) {
            UserNote note = notes.find(message.source().accountId(), message.senderId())
                    .orElse(null);
            summaries.add(summaryBuilder.build(message, note));
        }
        int totalChars = summaries.stream()
                .mapToInt(summary -> summary.summary().length())
                .sum();
        int first = 0;
        while (totalChars > DEFAULT_CONTEXT_MAX_CHARS && first < summaries.size()) {
            totalChars -= summaries.get(first++).summary().length();
        }
        return first == 0 ? List.copyOf(summaries)
                : List.copyOf(summaries.subList(first, summaries.size()));
    }

    /** 按 thread 的最新活跃度分批 flush；用于定时器一次扫描。 */
    public synchronized List<FlushResult> flushAll(int threadLimit) {
        if (threadLimit < 1 || threadLimit > 1000) {
            throw new IllegalArgumentException("threadLimit 必须在 1..1000");
        }
        List<FlushResult> results = new ArrayList<>();
        for (ChatThread thread : board.threads(threadLimit)) {
            results.add(flushThread(thread.threadId()));
        }
        long published = results.stream().filter(FlushResult::published).count();
        OpsLog.debug("event=inbox_flush_all threads={} published={}",
                results.size(), published);
        return List.copyOf(results);
    }

    /** 来源权重变化时立即调整当前 active item 的排序权重。 */
    public synchronized void adjustActivePriority(String threadId, int delta) {
        if (threadId == null || threadId.isBlank() || delta == 0) {
            return;
        }
        Optional<InboxDigestState> state = states.find(threadId);
        if (state.isEmpty() || !state.orElseThrow().hasActiveItem()) {
            return;
        }
        InboxDigestState current = state.orElseThrow();
        Optional<InboxDigestItem> item = port.view(current.itemId());
        if (item.isEmpty() || !item.orElseThrow().available()) {
            return;
        }
        InboxDigestItem available = item.orElseThrow();
        int priority = Math.max(-1000, Math.min(1000,
                available.priority() + delta));
        long revision = port.updatePriority(
                available.itemId(), available.revision(), priority);
        states.save(new InboxDigestState(threadId, available.itemId(),
                revision, current.lastSeq()));
        OpsLog.info("event=inbox_priority_adjusted threadId={} itemId={} "
                        + "oldPriority={} newPriority={} revision={}",
                threadId, available.itemId(), available.priority(), priority, revision);
    }

    /**
     * 按 consumer 已投递到的位置显式 ack Inbox。
     *
     * <p>Agent 只提供 {@code deliveredSeq} 这个服务端事实；itemId、revision 和 snapshot
     * 都从当前 {@code inbox_digest_state} 与 Inbox item 内部推导。deliveredSeq 早于当前
     * active item 范围时不改动 item；落在范围内时复用现有 partial/full ack 逻辑；新于
     * item 范围时先 flushThread 拉平。没有 active item 时只推进 digest cursor，避免
     * 已读消息以后再次生成未读 item。</p>
     */
    public synchronized AckResult ackThrough(String threadId, long deliveredSeq) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (deliveredSeq < 0L) {
            throw new IllegalArgumentException("deliveredSeq 不能为负");
        }
        try {
            Optional<InboxDigestState> initial = states.find(threadId);
            if (initial.isPresent() && initial.orElseThrow().hasActiveItem()) {
                InboxDigestItem item = viewAvailable(initial.orElseThrow().itemId());
                if (deliveredSeq > item.toSeq()) {
                    // 消息板可能已有未 flush 的消息；先把 active item 拉平到最新范围。
                    flushThread(threadId);
                }
            }

            InboxDigestState state = states.find(threadId)
                    .orElseGet(() -> InboxDigestState.cursorOnly(threadId, 0L));
            if (!state.hasActiveItem()) {
                long cursor = Math.max(state.lastSeq(), deliveredSeq);
                if (cursor > state.lastSeq()) {
                    states.save(InboxDigestState.cursorOnly(threadId, cursor));
                }
                OpsLog.info("event=inbox_ack_through threadId={} deliveredSeq={} "
                                + "outcome=cursor_only cursor={}",
                        threadId, deliveredSeq, cursor);
                return new AckResult(deliveredSeq, false, "", 0L);
            }

            InboxDigestItem item = viewAvailable(state.itemId());
            if (deliveredSeq <= 0L || deliveredSeq < item.fromSeq()) {
                // delivered 范围早于 active unread item；不能为了 ack 丢掉更新的未读。
                OpsLog.info("event=inbox_ack_through threadId={} deliveredSeq={} "
                                + "outcome=before_active_range itemId={} itemFromSeq={} "
                                + "itemToSeq={}", threadId, deliveredSeq, item.itemId(),
                        item.fromSeq(), item.toSeq());
                return new AckResult(deliveredSeq, false, item.itemId(), item.toSeq());
            }

            if (deliveredSeq >= item.toSeq()) {
                // CONSUMER 已处理到 item 末尾之后：以 deliveredSeq 作为 cursor 归档，
                // 让后续 flush 只为 deliveredSeq 之后的新消息建立未读 item。
                archiveAndCursor(threadId, item.itemId(), item.revision(), deliveredSeq);
                InboxDigestState after = states.find(threadId)
                        .orElseGet(() -> InboxDigestState.cursorOnly(threadId, 0L));
                if (after.hasActiveItem()) {
                    InboxDigestItem remainingItem = viewAvailable(after.itemId());
                    OpsLog.info("event=inbox_ack_through threadId={} deliveredSeq={} "
                                    + "outcome=archived_with_remaining remainingItemId={} "
                                    + "remainingToSeq={}", threadId, deliveredSeq,
                            remainingItem.itemId(), remainingItem.toSeq());
                    return new AckResult(deliveredSeq, true, remainingItem.itemId(),
                            remainingItem.toSeq());
                }
                OpsLog.info("event=inbox_ack_through threadId={} deliveredSeq={} "
                                + "outcome=archived cursor={}", threadId, deliveredSeq,
                        deliveredSeq);
                return new AckResult(deliveredSeq, true, "", 0L);
            }

            AckResult result = consumeSnapshot(threadId, item.itemId(), item.revision(),
                    deliveredSeq, state.lastSeq());
            OpsLog.info("event=inbox_ack_through threadId={} deliveredSeq={} "
                            + "outcome=applied archived={} remainingToSeq={}",
                    threadId, deliveredSeq, result.archived(), result.remainingToSeq());
            return result;
        } catch (InboxDigestException failure) {
            OpsLog.warn("event=inbox_ack_through_failed threadId={} deliveredSeq={} "
                            + "code={}", threadId, deliveredSeq, failure.code());
            throw failure;
        }
    }

    /**
     * 不经过 read 直接消费当前 active Inbox item；以 item 的入站 toSeq 为边界。
     * 没有 active item 时返回未归档空结果。Agent 根据 Inbox 摘要直接 ack 时使用。
     */
    public synchronized AckResult ackCurrent(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        Optional<InboxDigestState> state = states.find(threadId);
        if (state.isEmpty() || !state.orElseThrow().hasActiveItem()) {
            return new AckResult(0L, false, "", 0L);
        }
        InboxDigestItem item = viewAvailable(state.orElseThrow().itemId());
        return ackThrough(threadId, item.toSeq());
    }

    /** 主 Agent 处理完一个 digest 后归档，并保留已处理到的 thread 游标。 */
    public synchronized AckResult markHandled(String threadId, String itemId,
                                               long expectedRevision) {
        return markHandled(threadId, itemId, expectedRevision, 0L);
    }

    /**
     * 按 inbox_view 看到的 snapshotToSeq 归档；item 在查看后继续更新时，
     * 只消费 snapshot 范围，把剩余消息留在 item 中，避免无限追 revision。
     */
    public synchronized AckResult markHandled(String threadId, String itemId,
                                               long expectedRevision, long snapshotToSeq) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        Optional<InboxDigestState> state = states.find(threadId);
        if (state.isPresent() && !state.orElseThrow().itemId().equals(itemId)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("itemId", itemId);
            details.put("expectedItemId", state.orElseThrow().itemId());
            throw new InboxDigestConflictException(
                    "THREAD_ITEM_MISMATCH",
                    "itemId 不属于该 thread 的 active digest",
                    details,
                    null);
        }
        InboxDigestItem current = viewAvailable(itemId);
        if (snapshotToSeq <= 0L && expectedRevision != current.revision()) {
            throw revisionConflict(current, expectedRevision);
        }
        long stateLastSeq = state.map(InboxDigestState::lastSeq).orElse(0L);
        long snapshot = snapshotToSeq > 0L ? snapshotToSeq
                : current.toSeq() > 0L ? current.toSeq() : stateLastSeq;
        validateSnapshot(current, snapshot);

        if (snapshot < current.toSeq()) {
            return consumeSnapshot(threadId, itemId, expectedRevision, snapshot, stateLastSeq);
        }
        archiveAndCursor(threadId, itemId, current.revision(), snapshot);
        return new AckResult(snapshot, true, "", 0L);
    }

    private AckResult consumeSnapshot(String threadId, String itemId, long expectedRevision,
                                      long snapshot, long stateLastSeq) {
        InboxDigestItem current = viewAvailable(itemId);
        validateSnapshot(current, snapshot);
        if (snapshot >= current.toSeq()) {
            archiveAndCursor(threadId, itemId, current.revision(), snapshot);
            return new AckResult(snapshot, true, "", 0L);
        }
        UnreadWindow window = unreadWindow(threadId, snapshot, maxMessagesPerFlush);
        if (window.preview().isEmpty()) {
            // Only OUT messages remain after the snapshot: no unread item to keep.
            archiveAndCursor(threadId, itemId, current.revision(), current.toSeq());
            return new AckResult(current.toSeq(), true, "", 0L);
        }
        InboxTrigger previous = current.digest() == null ? null : current.digest().trigger();
        InboxDigest remainder = buildDigest(threadId, window, previous);
        try {
            InboxDigestItem updated = port.update(itemId, current.revision(), remainder);
            states.save(new InboxDigestState(threadId, itemId,
                    updated.revision(), window.toSeq()));
            recordAudit(threadId, updated, remainder, "available", clock.instant());
            OpsLog.info("event=inbox_ack threadId={} itemId={} outcome=remaining "
                            + "digestedThroughSeq={} remainingToSeq={} unreadCount={} revision={}",
                    threadId, itemId, snapshot, remainder.toSeq(),
                    remainder.unreadCount(), updated.revision());
            return new AckResult(snapshot, false, itemId, remainder.toSeq());
        } catch (InboxDigestConflictException conflict) {
            // Only one retry, against the freshest item revision.
            InboxDigestItem latest = viewAvailable(itemId);
            validateSnapshot(latest, snapshot);
            if (snapshot >= latest.toSeq()) {
                archiveAndCursor(threadId, itemId, latest.revision(), snapshot);
                return new AckResult(snapshot, true, "", 0L);
            }
            UnreadWindow retryWindow = unreadWindow(threadId, snapshot, maxMessagesPerFlush);
            if (retryWindow.preview().isEmpty()) {
                archiveAndCursor(threadId, itemId, latest.revision(), latest.toSeq());
                return new AckResult(latest.toSeq(), true, "", 0L);
            }
            InboxTrigger retryPrevious = latest.digest() == null
                    ? null : latest.digest().trigger();
            InboxDigest retryDigest = buildDigest(threadId, retryWindow, retryPrevious);
            InboxDigestItem updated = port.update(itemId, latest.revision(), retryDigest);
            states.save(new InboxDigestState(threadId, itemId,
                    updated.revision(), retryWindow.toSeq()));
            recordAudit(threadId, updated, retryDigest, "available", clock.instant());
            OpsLog.info("event=inbox_ack threadId={} itemId={} outcome=remaining "
                            + "digestedThroughSeq={} remainingToSeq={} unreadCount={} revision={} retried=true",
                    threadId, itemId, snapshot, retryDigest.toSeq(),
                    retryDigest.unreadCount(), updated.revision());
            return new AckResult(snapshot, false, itemId, retryDigest.toSeq());
        }
    }

    private InboxDigestItem viewAvailable(String itemId) {
        InboxDigestItem current = port.view(itemId).orElseThrow(() ->
                new InboxDigestConflictException("ITEM_NOT_FOUND",
                        "Inbox item 不存在或已归档", Map.of(), null));
        if (!current.available()) {
            throw statusConflict(current);
        }
        return current;
    }

    private void validateSnapshot(InboxDigestItem item, long snapshot) {
        if (snapshot <= 0L) {
            throw new InboxDigestConflictException("SNAPSHOT_REQUIRED",
                    "snapshotToSeq 必须为正", Map.of("itemId", item.itemId()), null);
        }
        if (item.fromSeq() > 0L && snapshot < item.fromSeq()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("itemId", item.itemId());
            details.put("itemFromSeq", item.fromSeq());
            details.put("snapshotToSeq", snapshot);
            throw new InboxDigestConflictException("SNAPSHOT_STALE",
                    "snapshotToSeq 早于当前 item 范围，请重新 inbox_view", details, null);
        }
        if (item.toSeq() > 0L && snapshot > item.toSeq()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("itemId", item.itemId());
            details.put("itemToSeq", item.toSeq());
            details.put("snapshotToSeq", snapshot);
            throw new InboxDigestConflictException("SNAPSHOT_AHEAD",
                    "snapshotToSeq 超出当前 item 范围，请重新 inbox_view", details, null);
        }
    }

    private void archiveAndCursor(String threadId, String itemId,
                                  long currentRevision, long cursor) {
        port.archive(itemId, currentRevision);
        // Use the acknowledged snapshot, not a possibly newer local cursor:
        // messages that arrived after the snapshot must become a new unread item.
        long effectiveCursor = Math.max(0L, cursor);
        states.save(InboxDigestState.cursorOnly(threadId, effectiveCursor));
        OpsLog.debug("event=inbox_cursor_advanced threadId={} itemId={} cursor={}",
                threadId, itemId, effectiveCursor);
        OpsLog.info("event=inbox_ack threadId={} itemId={} outcome=archived "
                        + "revision={} digestedThroughSeq={}", threadId, itemId,
                currentRevision, effectiveCursor);
        if (auditStore != null) {
            try {
                auditStore.markDone(threadId, itemId, clock.instant());
            } catch (RuntimeException failure) {
                // 审计是派生数据，不能回滚已完成的 Inbox 归档。
                OpsLog.warn("event=inbox_audit_failed threadId={} itemId={} "
                                + "status=done errorType={}", threadId, itemId,
                        failure.getClass().getSimpleName());
            }
        }
        // 查看期间到达的消息仍可能在消息板中；归档后立即尝试建立下一版
        // 未读桶。发布失败不回滚已成功的 ack，下一次新消息或 flushAll 会重试。
        try {
            flushThread(threadId);
        } catch (RuntimeException failure) {
            // 保持 cursorOnly；调度器/下一次消息会重试。
            OpsLog.warn("event=inbox_post_ack_flush_failed threadId={} itemId={} "
                            + "errorType={}", threadId, itemId,
                    failure.getClass().getSimpleName());
        }
    }

    private static InboxDigestConflictException revisionConflict(
            InboxDigestItem item, long expectedRevision) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("itemId", item.itemId());
        details.put("itemRevision", item.revision());
        details.put("expectedRevision", expectedRevision);
        return new InboxDigestConflictException(
                "ITEM_REVISION_CONFLICT",
                "Inbox digest revision 已变化，请重新 inbox_view",
                details,
                null);
    }

    private static InboxDigestConflictException statusConflict(InboxDigestItem item) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("itemId", item.itemId());
        details.put("itemStatus", item.status());
        details.put("allowedActions", item.available()
                ? List.of("update", "append", "delete")
                : "claimed".equalsIgnoreCase(item.status())
                        ? List.of("complete", "release")
                        : List.of());
        return new InboxDigestConflictException(
                "ITEM_STATUS_CONFLICT",
                "active digest item 状态不允许该操作: " + item.status(),
                details,
                null);
    }

    private static String activeDedupeKey(String threadId) {
        return "qq-thread:" + threadId + ":active";
    }

    /** ack 的结果；remaining 为 true 时 item 仍保留后续消息。 */
    public record AckResult(
            long digestedThroughSeq,
            boolean archived,
            String remainingItemId,
            long remainingToSeq) {

        public AckResult {
            if (digestedThroughSeq < 0L || remainingToSeq < 0L) {
                throw new IllegalArgumentException("seq 不能为负");
            }
            remainingItemId = remainingItemId == null ? "" : remainingItemId;
        }
    }

    /** 一次 flush 的结果；不发布时 itemId 为空。 */
    public record FlushResult(
            String threadId,
            boolean published,
            String itemId,
            long fromSeq,
            long toSeq,
            int messageCount,
            int priority) {

        public FlushResult {
            threadId = Objects.requireNonNull(threadId, "threadId");
            itemId = itemId == null ? "" : itemId.trim();
            if (fromSeq < 0L || toSeq < 0L) {
                throw new IllegalArgumentException("seq 不能为负");
            }
            if (messageCount < 0) {
                throw new IllegalArgumentException("messageCount 不能为负");
            }
            if (published && itemId.isEmpty()) {
                throw new IllegalArgumentException("已发布结果必须带 itemId");
            }
        }
    }
}
