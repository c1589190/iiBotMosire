package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.util.List;
import java.util.Objects;

/** GM 统一工单 MCP 工具集；完整 Ticket 装配下取代旧 complaint 三工具。 */
public final class GmTicketMcpTools {

    public static final List<String> NAMES = List.of(
            GmTicketBoardTool.NAME,
            GmTicketListTool.NAME,
            GmTicketGetTool.NAME,
            GmTicketClaimTool.NAME,
            GmTicketReplyTool.NAME,
            GmTicketBatchReplyTool.NAME,
            GmTicketCloseTool.NAME,
            GmTicketNoteCreateTool.NAME);

    private GmTicketMcpTools() {
    }

    public static List<AgentTool> all(TicketService ticketService, Actor supervisor) {
        Objects.requireNonNull(ticketService, "ticketService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                new GmTicketBoardTool(ticketService),
                new GmTicketListTool(ticketService),
                new GmTicketGetTool(ticketService),
                new GmTicketClaimTool(ticketService, supervisor),
                new GmTicketReplyTool(ticketService, supervisor),
                new GmTicketBatchReplyTool(ticketService, supervisor),
                new GmTicketCloseTool(ticketService, supervisor),
                new GmTicketNoteCreateTool(ticketService, supervisor));
    }
}
