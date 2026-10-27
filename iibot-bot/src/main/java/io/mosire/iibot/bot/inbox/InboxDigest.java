package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.summary.MessageSummary;
import io.mosire.iibot.bot.weight.InboxWeight;

import java.util.List;
import java.util.Objects;

/**
 * 一个来源 thread 的 Inbox 大框投影。
 *
 * <p>{@code messages} 是本次未处理的 bounded 摘要；{@code context} 是之前固定的
 * 少量已处理上下文，只帮助理解语义，不参与游标推进。</p>
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
        InboxWeight weight) {

    /** 兼容无 context 的 6 参构造。 */
    public InboxDigest(String threadId, long fromSeq, long toSeq,
                       List<MessageSummary> messages, String summary, int priority) {
        this(threadId, fromSeq, toSeq, messages, summary, priority, List.of(),
                "SOURCE_WEIGHT", InboxWeight.none(priority));
    }

    /** 兼容无权重解释的 7 参构造。 */
    public InboxDigest(String threadId, long fromSeq, long toSeq,
                       List<MessageSummary> messages, String summary, int priority,
                       List<MessageSummary> context) {
        this(threadId, fromSeq, toSeq, messages, summary, priority, context,
                "SOURCE_WEIGHT", InboxWeight.none(priority));
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
        fromSeq = messages.getFirst().localSeq();
        toSeq = messages.getLast().localSeq();
        if (!context.isEmpty() && context.getLast().localSeq() >= fromSeq) {
            throw new IllegalArgumentException("context 必须早于本次 messages");
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
    }

    /** 从一组未处理摘要构造大框（无历史上下文）。 */
    public static InboxDigest of(List<MessageSummary> messages) {
        return of(messages, List.of());
    }

    /** 从本次未处理摘要与固定上下文构造大框。 */
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
                InboxWeight.none(priority));
    }

    /** 带权重解释构造大框。 */
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
                weight);
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
