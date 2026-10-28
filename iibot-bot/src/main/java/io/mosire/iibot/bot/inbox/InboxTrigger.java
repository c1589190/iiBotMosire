package io.mosire.iibot.bot.inbox;

import java.util.Objects;

/**
 * 触发当前 Inbox 未读桶的代表消息。
 *
 * <p>它只保存定位和 bounded 摘要，完整正文仍在消息板。Agent 可直接用
 * {@code threadId + messageId} 调回复工具，或用 {@code replyToMessageId}
 * 追引用链。</p>
 */
public record InboxTrigger(String messageId, long localSeq, String senderId,
                           String senderName, String replyToMessageId,
                           String summary, String wakeReason) {

    public InboxTrigger {
        messageId = messageId == null ? "" : messageId.trim();
        if (localSeq <= 0L) {
            throw new IllegalArgumentException("localSeq 必须为正");
        }
        senderId = requireText(senderId, "senderId");
        senderName = senderName == null ? "" : senderName.trim();
        replyToMessageId = replyToMessageId == null ? "" : replyToMessageId.trim();
        summary = requireText(summary, "summary");
        wakeReason = requireText(wakeReason, "wakeReason");
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
