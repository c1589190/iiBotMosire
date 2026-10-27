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

    /** 处理一个 thread 自上次 flush 后的消息。 */
    public synchronized FlushResult flushThread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        Optional<InboxDigestState> state = states.find(threadId);
        long afterSeq = state.map(InboxDigestState::lastSeq).orElse(0L);
        Optional<InboxDigestItem> activeItem = Optional.empty();
        if (state.isPresent() && state.orElseThrow().hasActiveItem()) {
            activeItem = port.view(state.orElseThrow().itemId());
            if (activeItem.isPresent()) {
                InboxDigestItem active = activeItem.orElseThrow();
                if (!active.available()) {
                    throw statusConflict(active);
                }
                reconcileActive(threadId, state.orElseThrow(), active);
                // view-ack：active item 一旦发布就冻结，新消息留到 ack 后重新发布，
                // 避免 agent 处理期间 revision 持续变化和 wake/正文漂移。
                long frozenSeq = states.find(threadId)
                        .map(InboxDigestState::lastSeq).orElse(state.orElseThrow().lastSeq());
                return new FlushResult(threadId, false, "", frozenSeq, frozenSeq, 0, 0);
            }
        }
        List<ChatMessage> messages = board.readThread(threadId, afterSeq, maxMessagesPerFlush);
        if (messages.isEmpty()) {
            if (state.isPresent() && afterSeq > state.orElseThrow().lastSeq()) {
                states.save(state.orElseThrow().withLastSeq(afterSeq));
            }
            return new FlushResult(threadId, false, "", afterSeq, afterSeq, 0, 0);
        }

        long lastSeq = messages.getLast().localSeq();
        List<MessageSummary> summaries = summariesFor(messages, true);
        if (summaries.isEmpty()) {
            InboxDigestState cursor = state.map(value -> value.withLastSeq(lastSeq))
                    .orElseGet(() -> InboxDigestState.cursorOnly(threadId, lastSeq));
            states.save(cursor);
            return new FlushResult(threadId, false, "", afterSeq, lastSeq, 0, 0);
        }

        List<MessageSummary> context = contextBefore(
                threadId, summaries.getFirst().localSeq());
        InboxDigest digest = digestOf(threadId, summaries, context, messages);
        InboxDigestItem item = port.push(activeDedupeKey(threadId), digest);
        long stateLastSeq = Math.max(digest.toSeq(), lastSeq);
        InboxDigestState next = new InboxDigestState(threadId, item.itemId(),
                item.revision(), stateLastSeq);
        states.save(next);
        recordAudit(threadId, item, digest, "available", clock.instant());
        if (weights != null && "FIRST_CONTACT".equals(digest.wakeReason())) {
            weights.markBootstrapDelivered(threadId);
        }
        return new FlushResult(threadId, true, item.itemId(), digest.fromSeq(),
                digest.toSeq(), digest.messages().size(), digest.priority());
    }

    /**
     * 旧版本可能在 state.item 和 item 内容之间留下 seq 空洞（例如 state=601、
     * item=606..609）。部署新逻辑后先用消息板补齐 item，再冻结，避免 ack 跳消息。
     */
    private void reconcileActive(String threadId, InboxDigestState state,
                                  InboxDigestItem active) {
        long stateLast = state.lastSeq();
        long itemFrom = active.fromSeq();
        if (itemFrom > 0L && itemFrom > stateLast + 1L) {
            List<ChatMessage> messages = board.readThread(threadId, stateLast,
                    maxMessagesPerFlush);
            if (messages.isEmpty()) {
                return;
            }
            long lastRead = messages.getLast().localSeq();
            List<MessageSummary> summaries = summariesFor(messages, true);
            if (!summaries.isEmpty()) {
                InboxDigest digest = digestOf(threadId, summaries,
                        contextBefore(threadId, summaries.getFirst().localSeq()), messages);
                InboxDigestItem updated = port.update(
                        active.itemId(), active.revision(), digest);
                long reconciledSeq = Math.max(digest.toSeq(), lastRead);
                states.save(new InboxDigestState(threadId, updated.itemId(),
                        updated.revision(), reconciledSeq));
                recordAudit(threadId, updated, digest, "available", clock.instant());
            } else {
                states.save(new InboxDigestState(threadId, active.itemId(),
                        active.revision(), Math.max(stateLast, lastRead)));
            }
            return;
        }
        // 没有空洞时，item 已经覆盖到 active.toSeq；让 state 与 Inbox 视图对齐。
        long itemLast = active.toSeq();
        if (itemLast > stateLast || active.revision() != state.revision()) {
            states.save(new InboxDigestState(threadId, active.itemId(),
                    active.revision(), Math.max(stateLast, itemLast)));
        }
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
        } catch (RuntimeException ignored) {
            // 审计失败不回滚 Inbox 发布。
        }
    }

    private List<MessageSummary> summariesFor(List<ChatMessage> messages,
                                               boolean actionableOnly) {
        List<MessageSummary> summaries = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.direction() != ChatDirection.IN) {
                continue;
            }
            if (actionableOnly && weights != null
                    && !weights.actionableOrLegacy(message.messageKey())) {
                continue;
            }
            UserNote note = notes.find(message.source().accountId(), message.senderId())
                    .orElse(null);
            summaries.add(summaryBuilder.build(message, note));
        }
        return List.copyOf(summaries);
    }

    private InboxDigest digestOf(String threadId, List<MessageSummary> summaries,
                                 List<MessageSummary> context,
                                 List<ChatMessage> sourceMessages) {
        if (weights == null) {
            return InboxDigest.of(summaries, context);
        }
        Map<String, MessageWeightDecision> decisions = weights.decisions(
                sourceMessages.stream().map(ChatMessage::messageKey).toList());
        MessageWeightDecision strongest = decisions.values().stream()
                .filter(MessageWeightDecision::actionable)
                .max(java.util.Comparator.comparingInt(MessageWeightDecision::totalScore))
                .orElse(null);
        if (strongest == null) {
            return InboxDigest.of(summaries, context);
        }
        SourceWeightPolicy policy = weights.findPolicy(threadId).orElse(null);
        int threshold = policy == null ? strongest.totalScore() : policy.notifyThreshold();
        int priority = strongest.bootstrap()
                ? Math.max(strongest.totalScore(), threshold)
                : strongest.totalScore();
        InboxWeight weight = new InboxWeight(
                strongest.sourceWeight(), strongest.signalWeight(),
                strongest.keywordWeight(), priority, threshold,
                strongest.matchedSignals(), strongest.matchedKeywords());
        return InboxDigest.of(summaries, context,
                strongest.wakeReason().name(), weight);
    }

    private List<MessageSummary> contextBefore(String threadId, long beforeSeq) {
        List<ChatMessage> previous = board.readThreadBefore(
                threadId, beforeSeq, DEFAULT_CONTEXT_MESSAGES);
        List<MessageSummary> summaries = new ArrayList<>();
        for (ChatMessage message : previous) {
            if (weights != null && !weights.actionableOrLegacy(message.messageKey())) {
                continue;
            }
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
        List<MessageSummary> remaining = summariesAfter(threadId, snapshot);
        if (remaining.isEmpty()) {
            // 剩余只有 OUT 消息：没有需要再唤醒的入站摘要，直接推进到 item 末尾。
            archiveAndCursor(threadId, itemId, current.revision(), current.toSeq());
            return new AckResult(current.toSeq(), true, "", 0L);
        }
        InboxDigest remainder = InboxDigest.of(
                remaining, contextBefore(threadId, remaining.getFirst().localSeq()));
        try {
            InboxDigestItem updated = port.update(itemId, current.revision(), remainder);
            states.save(new InboxDigestState(threadId, itemId,
                    updated.revision(), remainder.toSeq()));
            recordAudit(threadId, updated, remainder, "available", clock.instant());
            return new AckResult(snapshot, false, itemId, remainder.toSeq());
        } catch (InboxDigestConflictException conflict) {
            // 只在真正的外部并发修改时重试一次；仍失败则 fail-closed。
            InboxDigestItem latest = viewAvailable(itemId);
            validateSnapshot(latest, snapshot);
            if (snapshot >= latest.toSeq()) {
                archiveAndCursor(threadId, itemId, latest.revision(), snapshot);
                return new AckResult(snapshot, true, "", 0L);
            }
            List<MessageSummary> retryRemaining = summariesAfter(threadId, snapshot);
            if (retryRemaining.isEmpty()) {
                archiveAndCursor(threadId, itemId, latest.revision(), latest.toSeq());
                return new AckResult(latest.toSeq(), true, "", 0L);
            }
            InboxDigest retryDigest = InboxDigest.of(retryRemaining,
                    contextBefore(threadId, retryRemaining.getFirst().localSeq()));
            InboxDigestItem updated = port.update(itemId, latest.revision(), retryDigest);
            states.save(new InboxDigestState(threadId, itemId,
                    updated.revision(), retryDigest.toSeq()));
            recordAudit(threadId, updated, retryDigest, "available", clock.instant());
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
        long effectiveCursor = Math.max(cursor,
                states.find(threadId).map(InboxDigestState::lastSeq).orElse(0L));
        states.save(InboxDigestState.cursorOnly(threadId, effectiveCursor));
        if (auditStore != null) {
            try {
                auditStore.markDone(threadId, itemId, clock.instant());
            } catch (RuntimeException ignored) {
                // 审计是派生数据，不能回滚已完成的 Inbox 归档。
            }
        }
        // 冻结期间到达的消息没有随旧 item 更新；归档后立即尝试发布下一版。
        // 发布失败不回滚已成功的 ack，下一次新消息或 flushAll 会重试。
        try {
            flushThread(threadId);
        } catch (RuntimeException ignored) {
            // 保持 cursorOnly；调度器/下一次消息会重试。
        }
    }

    private List<MessageSummary> summariesAfter(String threadId, long afterSeq) {
        List<ChatMessage> messages = board.readThread(threadId, afterSeq,
                maxMessagesPerFlush);
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
