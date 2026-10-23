package io.mosire.iibot.taskflow.complaint;

/**
 * GM 回应私聊投递的外部送达状态。
 *
 * <p>{@link #PENDING} 是回应写入后的初始状态；外部发送由宿主 outbox 执行。{@link #UNKNOWN}
 * 表示发送结果不确定，不得自动重置为 {@link #PENDING} 或自动重发。</p>
 */
public enum ComplaintDeliveryState {
    PENDING,
    SENT,
    UNKNOWN,
    FAILED
}
