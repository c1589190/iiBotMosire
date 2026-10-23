package io.mosire.iibot.taskflow.post;

/**
 * 已发布推文的外部送达状态。
 *
 * <p>外部发送由宿主 outbox 执行；本状态只记录结果。{@link #UNKNOWN} 表示发送结果不确定，
 * 不得自动重置为 {@link #PENDING} 或再次自动发送。</p>
 */
public enum DeliveryState {
    PENDING,
    SENT,
    UNKNOWN,
    FAILED
}
