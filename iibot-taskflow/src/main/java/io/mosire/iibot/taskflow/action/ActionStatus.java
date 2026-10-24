package io.mosire.iibot.taskflow.action;

/** 自然语言行动在行动池中的生命周期状态。 */
public enum ActionStatus {
    QUEUED,
    SELECTED,
    COMMITTED,
    WITHDRAWN
}
