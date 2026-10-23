package io.mosire.iibot.taskflow.post;

/** 推文审计动作类型；审计条目不保存推文正文。 */
public enum PostAuditAction {
    CREATE,
    APPROVE,
    REJECT,
    PUBLISH,
    DELIVERY
}
