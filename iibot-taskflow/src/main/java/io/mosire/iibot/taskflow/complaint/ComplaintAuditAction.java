package io.mosire.iibot.taskflow.complaint;

/** 投诉审计动作类型；审计条目不保存投诉或回应正文。 */
public enum ComplaintAuditAction {
    SUBMIT,
    RESPOND,
    DELIVERY
}
