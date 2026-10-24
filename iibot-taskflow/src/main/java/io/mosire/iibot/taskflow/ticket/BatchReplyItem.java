package io.mosire.iibot.taskflow.ticket;

/** 批量回复输入项；expectedRevision 必须匹配原工单版本。 */
public record BatchReplyItem(String ticketId, long expectedRevision, String replyBody) {
    public BatchReplyItem {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId 不能为空");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负");
        }
        if (replyBody == null || replyBody.isBlank()) {
            throw new IllegalArgumentException("replyBody 不能为空");
        }
    }
}
