package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.summary.MessageSummary;
import io.mosire.iibot.bot.weight.InboxWeight;

import java.util.List;
import java.util.Objects;

/**
 * 一个来源 thread 的 Inbox 未读桶投影。
 *
 * <p>{@code messages} 是给 Agent 的 bounded 未读预览；{@code fromSeq}/{@code toSeq}
 * 表示完整未读范围，{@code unreadCount} 是实际未读入站数量，可能大于预览条数。
 * 完整正文和历史始终由消息板工具读取，不复制进 Inbox。</p>
 *
 * <p>{@code context} 是当前未读范围之前的少量上下文，只帮助理解指代，不参与游标推进。</p>
 *
 * <p>{@code summaryComplete=true} 表示 {@code summary} 已包含当前未读范围的全部可读
 * 文本；仍由 Bot 内部映射为 Inbox {@code kind=digest_complete}，Inbox core 不解释该字段。</p>
 */
public record InboxDigest(
        String threadId,
        long fromSeq,
        long toSeq,
        List<MessageSummary> messages,
        String summary,
        int priority,
        List<MessageSummary> context,
        String wakeReason,
        InboxWeight weight,
        InboxSource source,
        InboxTrigger trigger,
        int unreadCount,
        boolean hasMoreUnread,
        boolean summaryComplete) {

    /** 兼容无 context 的 6 参构造。 */
    public InboxDigest(String threadId, long fromSeq, long toSeq,
                       List<MessageSummary> messages, String summary, int priority) {
        this(threadId, fromSeq, toSeq, messages, summary, priority, List.of(),
                "SOURCE_WEIGHT", InboxWeight.none(priority), null, null,
                messages == null ? 0 : messages.size(), false, false);
    }

    /** 兼容无权重解释的 7 参构造。 */
    public InboxDigest(String threadId, long fromSeq, long toSeq,
                       List<MessageSummary> messages, String summary, int priority,
                       List<MessageSummary> context) {
        this(threadId, fromSeq, toSeq, messages, summary, priority, context,
                "SOURCE_WEIGHT", InboxWeight.none(priority), null, null,
                messages == null ? 0 : messages.size(), false, false);
    }

    /** 兼容旧 9 参构造。 */
    public InboxDigest(String threadId, long fromSeq, long toSeq,
                       List<MessageSummary> messages, String summary, int priority,
                       List<MessageSummary> context, String wakeReason,
                       InboxWeight weight) {
        this(threadId, fromSeq, toSeq, messages, summary, priority, context,
                wakeReason, weight, null, null,
                messages == null ? 0 : messages.size(), false, false);
    }

    public InboxDigest {
        threadId = requireText(threadId, "threadId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        context = context == null ? List.of() : List.copyOf(context);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        long previousSeq = -1L;
        for (MessageSummary message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("messages 不能包含 null");
            }
            if (!threadId.equals(message.threadId())) {
                throw new IllegalArgumentException("摘要不属于该 thread");
            }
            if (message.localSeq() <= previousSeq) {
                throw new IllegalArgumentException("摘要必须按 localSeq 严格升序");
            }
            previousSeq = message.localSeq();
        }
        long previousContextSeq = -1L;
        for (MessageSummary message : context) {
            if (message == null) {
                throw new IllegalArgumentException("context 不能包含 null");
            }
            if (!threadId.equals(message.threadId())) {
                throw new IllegalArgumentException("context 不属于该 thread");
            }
            if (message.localSeq() <= previousContextSeq) {
                throw new IllegalArgumentException("context 必须按 localSeq 严格升序");
            }
            previousContextSeq = message.localSeq();
        }
        if (fromSeq <= 0L) fromSeq = messages.getFirst().localSeq();
        if (toSeq <= 0L) toSeq = messages.getLast().localSeq();
        if (toSeq < fromSeq) {
            throw new IllegalArgumentException("toSeq 不能小于 fromSeq");
        }
        if (fromSeq > messages.getFirst().localSeq()) {
            throw new IllegalArgumentException("fromSeq 不能晚于预览首条消息");
        }
        if (toSeq < messages.getLast().localSeq()) {
            throw new IllegalArgumentException("toSeq 不能早于预览末条消息");
        }
        if (!context.isEmpty() && context.getLast().localSeq() >= fromSeq) {
            throw new IllegalArgumentException("context 必须早于本次未读范围");
        }
        summary = requireText(summary, "summary");
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
        wakeReason = wakeReason == null || wakeReason.isBlank()
                ? "SOURCE_WEIGHT" : wakeReason.trim();
        weight = weight == null ? InboxWeight.none(priority) : weight;
        if (weight.totalScore() != priority) {
            throw new IllegalArgumentException("weight.totalScore 必须等于 priority");
        }
        unreadCount = Math.max(unreadCount, messages.size());
        hasMoreUnread = hasMoreUnread || unreadCount > messages.size();
        if (summaryComplete && (hasMoreUnread || unreadCount > messages.size())) {
            throw new IllegalArgumentException(
                    "summaryComplete 不能与更多未读同时为 true");
        }
        if (source != null && !threadId.equals(source.threadId())) {
            throw new IllegalArgumentException("source 不属于该 thread");
        }
        if (trigger != null
                && (trigger.localSeq() < fromSeq || trigger.localSeq() > toSeq)) {
            throw new IllegalArgumentException("trigger 必须位于未读范围内");
        }
    }

    /** 从一组未处理摘要构造未读桶（无历史上下文）。 */
    public static InboxDigest of(List<MessageSummary> messages) {
        return of(messages, List.of());
    }

    /** 从本次未处理摘要与固定上下文构造未读桶。 */
    public static InboxDigest of(List<MessageSummary> messages,
                                 List<MessageSummary> context) {
        List<MessageSummary> copy = List.copyOf(messages);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        int priority = copy.stream().mapToInt(MessageSummary::priority).max().orElse(0);
        return new InboxDigest(
                copy.getFirst().threadId(),
                copy.getFirst().localSeq(),
                copy.getLast().localSeq(),
                copy,
                copy.getLast().summary(),
                priority,
                context,
                "SOURCE_WEIGHT",
                InboxWeight.none(priority),
                null,
                null,
                copy.size(),
                false,
                false);
    }

    /** 带权重解释构造未读桶。 */
    public static InboxDigest of(List<MessageSummary> messages,
                                 List<MessageSummary> context,
                                 String wakeReason,
                                 InboxWeight weight) {
        List<MessageSummary> copy = List.copyOf(messages);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        return new InboxDigest(
                copy.getFirst().threadId(),
                copy.getFirst().localSeq(),
                copy.getLast().localSeq(),
                copy,
                copy.getLast().summary(),
                weight.totalScore(),
                context,
                wakeReason,
                weight,
                null,
                null,
                copy.size(),
                false,
                false);
    }

    /** 带来源、trigger 和未读计数的构造。 */
    public static InboxDigest of(List<MessageSummary> messages,
                                 List<MessageSummary> context,
                                 String summary,
                                 String wakeReason,
                                 InboxWeight weight,
                                 InboxSource source,
                                 InboxTrigger trigger,
                                 int unreadCount,
                                 boolean hasMoreUnread) {
        List<MessageSummary> copy = List.copyOf(messages);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        return new InboxDigest(
                copy.getFirst().threadId(),
                copy.getFirst().localSeq(),
                copy.getLast().localSeq(),
                copy,
                summary,
                weight.totalScore(),
                context,
                wakeReason,
                weight,
                source,
                trigger,
                unreadCount,
                hasMoreUnread,
                false);
    }

    /** 带显式未读范围与预览的构造。 */
    public static InboxDigest of(long fromSeq, long toSeq,
                                 List<MessageSummary> messages,
                                 List<MessageSummary> context,
                                 String summary,
                                 String wakeReason,
                                 InboxWeight weight,
                                 InboxSource source,
                                 InboxTrigger trigger,
                                 int unreadCount,
                                 boolean hasMoreUnread) {
        List<MessageSummary> copy = List.copyOf(messages);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        return new InboxDigest(copy.getFirst().threadId(), fromSeq, toSeq,
                copy, summary, weight.totalScore(), context, wakeReason, weight,
                source, trigger, unreadCount, hasMoreUnread, false);
    }

    /** 带显式未读范围、摘要完整性标记的构造。 */
    public static InboxDigest of(long fromSeq, long toSeq,
                                 List<MessageSummary> messages,
                                 List<MessageSummary> context,
                                 String summary,
                                 String wakeReason,
                                 InboxWeight weight,
                                 InboxSource source,
                                 InboxTrigger trigger,
                                 int unreadCount,
                                 boolean hasMoreUnread,
                                 boolean summaryComplete) {
        List<MessageSummary> copy = List.copyOf(messages);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        return new InboxDigest(copy.getFirst().threadId(), fromSeq, toSeq,
                copy, summary, weight.totalScore(), context, wakeReason, weight,
                source, trigger, unreadCount, hasMoreUnread, summaryComplete);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
