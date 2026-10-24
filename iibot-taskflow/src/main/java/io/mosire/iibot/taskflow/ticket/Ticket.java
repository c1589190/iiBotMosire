package io.mosire.iibot.taskflow.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 统一工单。
 *
 * <p>正文与回复保存在快照/完整事件中；{@link #toString()} 只输出 ID/状态/时间等索引字段，
 * 不输出 title/body/reply。</p>
 */
public record Ticket(
        String ticketId,
        TicketType type,
        String playerId,
        String authorActorId,
        String title,
        String body,
        TicketStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        String claimedBy,
        List<TicketReply> replies,
        String closedBy,
        String closeReason) {

    public Ticket {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId 不能为空");
        }
        Objects.requireNonNull(type, "type");
        if (type == TicketType.GM_NOTE) {
            if (playerId != null) {
                throw new IllegalArgumentException("GM_NOTE playerId 必须为空");
            }
        } else if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("非 GM_NOTE 工单必须绑定 playerId");
        }
        if (authorActorId == null || authorActorId.isBlank()) {
            throw new IllegalArgumentException("authorActorId 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        Objects.requireNonNull(status, "status");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须为正");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
        if (claimedBy != null && claimedBy.isBlank()) {
            throw new IllegalArgumentException("claimedBy 提供时不能为空白");
        }
        replies = replies == null ? List.of() : List.copyOf(replies);
        for (TicketReply reply : replies) {
            Objects.requireNonNull(reply, "reply");
            if (reply.ticketId() != null && !reply.ticketId().equals(ticketId)) {
                throw new IllegalArgumentException("回复 ticketId 与工单不一致");
            }
        }
        if (status == TicketStatus.CLOSED) {
            if (closedBy == null || closedBy.isBlank()) {
                throw new IllegalArgumentException("CLOSED 工单必须记录 closedBy");
            }
            if (closeReason == null || closeReason.isBlank()) {
                throw new IllegalArgumentException("CLOSED 工单必须记录 closeReason");
            }
        } else if (closedBy != null || closeReason != null) {
            throw new IllegalArgumentException("未关闭工单不能带 closedBy/closeReason");
        }
    }

    @Override
    public String toString() {
        return "Ticket{ticketId=" + ticketId + ", type=" + type
                + ", playerId=" + playerId + ", status=" + status
                + ", revision=" + revision + ", authorActorId=" + authorActorId
                + ", claimedBy=" + claimedBy + ", replyCount=" + replies.size()
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + '}';
    }
}
