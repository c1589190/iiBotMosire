package io.mosire.iibot.taskflow.ticket;

import java.util.Objects;

/** 工单命令稳定回执；不包含标题、正文或回复正文。 */
public record TicketCommandResult(boolean ok, String code, String message,
                                  String ticketId, long revision) {

    public TicketCommandResult {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        message = message == null ? "" : message;
        if (revision < 0) {
            throw new IllegalArgumentException("revision 不能为负");
        }
    }

    public static TicketCommandResult success(String ticketId, long revision) {
        return new TicketCommandResult(true, TicketService.OK, "操作成功", ticketId, revision);
    }

    public static TicketCommandResult failure(String code, String message,
                                              String ticketId, long revision) {
        return new TicketCommandResult(false, Objects.requireNonNull(code, "code"),
                message, ticketId, Math.max(0L, revision));
    }
}
