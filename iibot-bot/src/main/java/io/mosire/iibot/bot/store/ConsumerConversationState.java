package io.mosire.iibot.bot.store;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个 consumer 对某个 thread 的服务端读取状态。
 *
 * <p>{@code readSeq} 是 Agent 已显式 ack 的位置；{@code deliveredSeq} 是最近一次
 * {@code qq_conversation_read} 已返回批次的最大 localSeq。二者都由服务端维护，不进入
 * Agent 工具参数。状态始终满足 {@code readSeq <= deliveredSeq}。</p>
 */
public record ConsumerConversationState(
        String consumerId,
        String threadId,
        long readSeq,
        long deliveredSeq,
        Instant updatedAt) {

    public ConsumerConversationState {
        consumerId = requireText(consumerId, "consumerId");
        threadId = requireText(threadId, "threadId");
        if (readSeq < 0L) {
            throw new IllegalArgumentException("readSeq 不能为负");
        }
        if (deliveredSeq < 0L) {
            throw new IllegalArgumentException("deliveredSeq 不能为负");
        }
        if (deliveredSeq < readSeq) {
            throw new IllegalArgumentException("deliveredSeq 不能小于 readSeq");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public ConsumerConversationState withDelivered(long newDeliveredSeq, Instant now) {
        if (newDeliveredSeq <= deliveredSeq) {
            return this;
        }
        return new ConsumerConversationState(
                consumerId, threadId, readSeq, newDeliveredSeq, now);
    }

    public ConsumerConversationState withRead(long newReadSeq, Instant now) {
        if (newReadSeq <= readSeq) {
            return this;
        }
        if (newReadSeq > deliveredSeq) {
            throw new IllegalArgumentException("readSeq 不能超过 deliveredSeq");
        }
        return new ConsumerConversationState(
                consumerId, threadId, newReadSeq, deliveredSeq, now);
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
