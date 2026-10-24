package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 读取单条工单：允许返回正文与 replies。 */
public final class GmTicketGetTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按 ticketId 读取工单正文与回复。",
            "properties", Map.of(
                    "ticketId", Map.of("type", "string", "description", "工单 ID")),
            "required", List.of("ticketId"),
            "additionalProperties", false);

    private final TicketService ticketService;

    public GmTicketGetTool(TicketService ticketService) {
        super(NAME, "读取 GM 工单正文与 replies；不存在返回稳定错误。",
                SCHEMA, Operation.READ, GmTicketToolSupport.readSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        String ticketId = GmTicketToolSupport.requiredText(arguments, "ticketId",
                GmTicketToolSupport.MAX_ID_CHARS);
        Ticket ticket = ticketService.getForGm(ticketId).orElse(null);
        if (ticket == null) {
            return GmTicketToolSupport.failure(NAME, TicketService.TICKET_NOT_FOUND,
                    "工单不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>(GmTicketToolSupport.detail(ticket));
        return GmTicketToolSupport.success(NAME, data);
    }
}
