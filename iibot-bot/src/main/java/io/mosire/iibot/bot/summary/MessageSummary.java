package io.mosire.iibot.bot.summary;

import io.mosire.iibot.bot.chat.ChatDirection;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 一条入站消息的 bounded 摘要，用于 Inbox 来源未读预览。
 *
 * <p>摘要不是事实源；完整正文始终由 messageId + localSeq 回到消息板读取。</p>
 */
public record MessageSummary(
        String threadId,
        long localSeq,
        String messageId,
        String messageKey,
        ChatDirection direction,
        String senderId,
        String senderName,
        Set<String> tags,
        String note,
        int priority,
        String summary,
        Instant sentAt,
        String replyToMessageId,
        String replyToSummary) {

    public MessageSummary {
        threadId = requireText(threadId, "threadId");
        if (localSeq <= 0L) {
            throw new IllegalArgumentException("localSeq 必须为正");
        }
        messageId = messageId == null ? "" : messageId.trim();
        messageKey = messageKey == null ? "" : messageKey.trim();
        direction = Objects.requireNonNull(direction, "direction");
        senderId = requireText(senderId, "senderId");
        senderName = senderName == null ? "" : senderName.trim();
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        note = note == null ? "" : note.trim();
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
        summary = requireText(summary, "summary");
        replyToMessageId = replyToMessageId == null ? "" : replyToMessageId.trim();
        replyToSummary = replyToSummary == null ? "" : replyToSummary.trim();
    }

    /** 兼容构造器：不带引用信息（旧内容反解与旧调用点）。 */
    public MessageSummary(
            String threadId, long localSeq, String messageId, String messageKey,
            ChatDirection direction, String senderId, String senderName, Set<String> tags,
            String note, int priority, String summary, Instant sentAt) {
        this(threadId, localSeq, messageId, messageKey, direction, senderId, senderName,
                tags, note, priority, summary, sentAt, "", "");
    }

    /**
     * 兼容构造器：不带 {@code messageKey}（旧调用点/旧内容反解）。
     *
     * <p>{@code messageKey} 是消息板内的稳定键，{@code qq_media_get} 只能用它取图；
     * 板里没有它时 Agent 无法从 digest 定位图片，所以新写入的板必须带上。</p>
     */
    public MessageSummary(
            String threadId, long localSeq, String messageId, ChatDirection direction,
            String senderId, String senderName, Set<String> tags, String note,
            int priority, String summary, Instant sentAt) {
        this(threadId, localSeq, messageId, "", direction, senderId, senderName, tags,
                note, priority, summary, sentAt, "", "");
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
