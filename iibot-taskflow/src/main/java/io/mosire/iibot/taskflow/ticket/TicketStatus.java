package io.mosire.iibot.taskflow.ticket;

/** 工单状态机：OPEN → CLAIMED → RESOLVED；三者均可进入 CLOSED。 */
public enum TicketStatus {
    OPEN,
    CLAIMED,
    RESOLVED,
    CLOSED
}
