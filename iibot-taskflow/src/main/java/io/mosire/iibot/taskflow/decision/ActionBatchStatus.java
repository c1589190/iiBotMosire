package io.mosire.iibot.taskflow.decision;

/** 推演批次状态；本层只记录执行器返回的结果。 */
public enum ActionBatchStatus {
    PENDING,
    COMMITTED,
    NO_OP,
    NEEDS_CLARIFICATION,
    REJECTED,
    CONFLICTED
}
