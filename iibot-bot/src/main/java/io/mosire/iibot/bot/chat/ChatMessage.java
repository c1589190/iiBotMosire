package io.mosire.iibot.bot.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * 消息板中的一条不可变消息。
 *
 * <p>{@code localSeq} 由消息板在入库事务中分配，是同一 thread 内排序的唯一依据；
 * 尚未入库的新消息传 0。{@code messageKey} 是持久去重键。</p>
 */
public record ChatMessage(
        String messageKey,
        ChatSourceKey source,
        long localSeq,
        ChatDirection direction,
        String senderId,
        String senderName,
        String messageId,
        Instant sentAt,
        Instant receivedAt,
        String text,
        String nonTextSummary,
        String replyToMessageId,
        DeliveryState deliveryState,
        String idempotencyKey) {

    public ChatMessage {
        messageKey = requireText(messageKey, "messageKey");
        source = Objects.requireNonNull(source, "source");
        if (localSeq < 0) {
            throw new IllegalArgumentException("localSeq 不能为负");
        }
        direction = Objects.requireNonNull(direction, "direction");
        senderId = requireText(senderId, "senderId");
        senderName = optionalText(senderName);
        messageId = optionalText(messageId);
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        text = normalizeBody(text);
        nonTextSummary = optionalText(nonTextSummary);
        replyToMessageId = optionalText(replyToMessageId);
        deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        idempotencyKey = optionalText(idempotencyKey);
        if (direction == ChatDirection.IN && deliveryState != DeliveryState.NONE) {
            throw new IllegalArgumentException("入站消息的 deliveryState 必须是 NONE");
        }
        if (direction == ChatDirection.OUT) {
            if (deliveryState == DeliveryState.NONE) {
                throw new IllegalArgumentException("出站消息必须携带投递状态");
            }
            if (idempotencyKey.isEmpty()) {
                throw new IllegalArgumentException("出站消息必须携带 idempotencyKey");
            }
        }
    }

    /** 构造一条尚未入库的入站消息。 */
    public static ChatMessage inbound(
            ChatSourceKey source,
            String messageId,
            String senderId,
            String senderName,
            Instant sentAt,
            Instant receivedAt,
            String text,
            String nonTextSummary,
            String replyToMessageId) {
        String stableMessageId = requireText(messageId, "messageId");
        return new ChatMessage(
                inboundKey(source, stableMessageId),
                source,
                0L,
                ChatDirection.IN,
                senderId,
                senderName,
                stableMessageId,
                sentAt,
                receivedAt,
                text,
                nonTextSummary,
                replyToMessageId,
                DeliveryState.NONE,
                "");
    }

    /** 构造一条尚未发送的出站消息。 */
    public static ChatMessage outbound(
            ChatSourceKey source,
            String idempotencyKey,
            Instant createdAt,
            String text,
            String nonTextSummary,
            String replyToMessageId) {
        String stableKey = requireText(idempotencyKey, "idempotencyKey");
        return new ChatMessage(
                outboundKey(source, stableKey),
                source,
                0L,
                ChatDirection.OUT,
                source.accountId(),
                "",
                "",
                null,
                createdAt,
                text,
                nonTextSummary,
                replyToMessageId,
                DeliveryState.PENDING,
                stableKey);
    }

    /** 入库后由消息板分配 localSeq。 */
    public ChatMessage withLocalSeq(long assignedSeq) {
        if (assignedSeq <= 0) {
            throw new IllegalArgumentException("assignedSeq 必须为正");
        }
        return new ChatMessage(messageKey, source, assignedSeq, direction, senderId, senderName,
                messageId, sentAt, receivedAt, text, nonTextSummary, replyToMessageId,
                deliveryState, idempotencyKey);
    }

    /** 更新出站投递状态并补外部 messageId/发送时间。 */
    public ChatMessage withDelivery(DeliveryState state, String externalMessageId,
                                    Instant sentAt) {
        if (direction != ChatDirection.OUT) {
            throw new IllegalStateException("入站消息不能更新投递状态");
        }
        return new ChatMessage(messageKey, source, localSeq, direction, senderId, senderName,
                externalMessageId == null ? messageId : externalMessageId, sentAt, receivedAt,
                text, nonTextSummary, replyToMessageId, state, idempotencyKey);
    }

    /** 入站消息的稳定去重键。 */
    public static String inboundKey(ChatSourceKey source, String messageId) {
        return "in:" + source.threadId() + ":" + requireText(messageId, "messageId");
    }

    /** 出站消息的稳定幂等键。 */
    public static String outboundKey(ChatSourceKey source, String idempotencyKey) {
        return "out:" + source.threadId() + ":" + requireText(idempotencyKey, "idempotencyKey");
    }

    private static String normalizeBody(String value) {
        return value == null ? "" : value;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
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
