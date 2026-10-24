package io.mosire.iibot.taskflow.ticket;

/** 工单状态机：OPEN → CLAIMED → RESOLVED；ACTION_DRAFT 可先进入 CONFIRMED；均可进入 CLOSED。 */
public enum TicketStatus {
    OPEN,
    CLAIMED,
    CONFIRMED,
    RESOLVED,
    CLOSED
}
