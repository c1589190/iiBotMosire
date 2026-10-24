package io.mosire.iibot.taskflow.ticket;

import java.time.Instant;
import java.util.Objects;

/**
 * 工单回复。
 *
 * <p>{@code ticketId} 可选，仅用于投影/审计便捷；领域内回复嵌在 Ticket 中，事实归属由
 * Ticket 本身确定。{@link #toString()} 不输出正文。</p>
 */
public record TicketReply(String ticketId, String replyId, String byActorId,
                          String body, Instant at) {

    public TicketReply {
        if (ticketId != null && ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId 提供时不能为空白");
        }
        if (replyId == null || replyId.isBlank()) {
            throw new IllegalArgumentException("replyId 不能为空");
        }
        if (byActorId == null || byActorId.isBlank()) {
            throw new IllegalArgumentException("byActorId 不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("回复正文不能为空");
        }
        Objects.requireNonNull(at, "at");
    }

    /** 规范形状：回复嵌在 Ticket 中时无需重复 ticketId。 */
    public TicketReply(String replyId, String byActorId, String body, Instant at) {
        this(null, replyId, byActorId, body, at);
    }

    @Override
    public String toString() {
        return "TicketReply{ticketId=" + ticketId + ", replyId=" + replyId
                + ", byActorId=" + byActorId + ", at=" + at + '}';
    }
}
