package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 工单列表：type/status/limit 可选；只返回摘要。 */
public final class GmTicketListTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按类型/状态/limit 列出工单摘要。",
            "properties", Map.of(
                    "type", Map.of("type", "string", "description", "可选工单类型"),
                    "status", Map.of("type", "string", "description", "可选工单状态"),
                    "limit", Map.of("type", "integer", "minimum", 1,
                            "maximum", GmTicketToolSupport.MAX_LIMIT,
                            "description", "最大返回条数，缺省 50")),
            "required", List.of(),
            "additionalProperties", false);

    private final TicketService ticketService;

    public GmTicketListTool(TicketService ticketService) {
        super(NAME, "列出 GM 工单摘要；不返回正文或回复正文。",
                SCHEMA, Operation.READ, GmTicketToolSupport.readSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        TicketType type = GmTicketToolSupport.optionalType(arguments);
        TicketStatus status = GmTicketToolSupport.optionalStatus(arguments);
        int limit = GmTicketToolSupport.optionalLimit(arguments,
                GmTicketToolSupport.DEFAULT_LIMIT);
        List<Map<String, Object>> items = new ArrayList<>();
        ticketService.listForGm(type, status, limit)
                .forEach(ticket -> items.add(GmTicketToolSupport.summary(ticket)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("type", type == null ? null : type.name());
        data.put("status", status == null ? null : status.name());
        data.put("limit", limit);
        data.put("tickets", items);
        return GmTicketToolSupport.success(NAME, data);
    }
}
