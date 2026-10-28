package io.mosire.iibot.bot.store;

import java.time.Instant;
import java.util.Optional;

/**
 * consumer 会话游标持久化端口。
 *
 * <p>第一版只有 {@link #DEFAULT_CONSUMER_ID} 一个 consumer；接口从一开始保留
 * consumerId 维度，避免以后多 Agent 隔离时重建状态模型。</p>
 */
public interface ConsumerConversationStore {

    String DEFAULT_CONSUMER_ID = "default";

    Optional<ConsumerConversationState> find(String consumerId, String threadId);

    /**
     * 记录一次 read 已返回批次的最大 localSeq。
     *
     * <p>deliveredSeq 只增不减；小于等于当前值的调用是幂等 no-op。</p>
     */
    ConsumerConversationState recordDelivered(
            String consumerId, String threadId, long deliveredSeq, Instant updatedAt);

    /**
     * 显式 ack 后推进 readSeq。
     *
     * <p>readSeq 只增不减，且不能超过当前 deliveredSeq。小于等于当前值的调用是幂等
     * no-op。</p>
     */
    ConsumerConversationState advanceRead(
            String consumerId, String threadId, long readSeq, Instant updatedAt);
}
