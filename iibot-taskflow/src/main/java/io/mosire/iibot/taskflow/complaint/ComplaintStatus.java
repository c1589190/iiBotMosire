package io.mosire.iibot.taskflow.complaint;

/**
 * 投诉状态机。
 *
 * <pre>
 * SUBMITTED → RESPONDED
 * </pre>
 *
 * <p>本版本不提供关闭状态；投诉提交不受回合/世界状态限制。</p>
 */
public enum ComplaintStatus {
    SUBMITTED,
    RESPONDED
}
