package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 工单板：按类型/状态计数 + 最近 OPEN/CLAIMED 摘要；不返回正文。 */
public final class GmTicketBoardTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_board";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "查看工单板计数与最近 OPEN/CLAIMED 工单摘要。",
            "properties", Map.of(),
            "required", List.of(),
            "additionalProperties", false);

    private final TicketService ticketService;

    public GmTicketBoardTool(TicketService ticketService) {
        super(NAME, "GM 工单板；只返回计数与摘要，不返回正文。",
                SCHEMA, Operation.READ, GmTicketToolSupport.readSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        List<Ticket> all = ticketService.snapshot().tickets();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (TicketType type : TicketType.values()) {
            byType.put(type.name(), 0);
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (TicketStatus status : TicketStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        for (Ticket ticket : all) {
            byType.compute(ticket.type().name(), (key, count) ->
                    count == null ? 1 : count + 1);
            byStatus.compute(ticket.status().name(), (key, count) ->
                    count == null ? 1 : count + 1);
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        all.stream()
                .filter(ticket -> ticket.status() == TicketStatus.OPEN
                        || ticket.status() == TicketStatus.CLAIMED
                        || ticket.status() == TicketStatus.CONFIRMED)
                .sorted(Comparator.comparing(Ticket::updatedAt).reversed()
                        .thenComparing(Ticket::ticketId))
                .limit(GmTicketToolSupport.RECENT_LIMIT)
                .forEach(ticket -> recent.add(GmTicketToolSupport.summary(ticket)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", all.size());
        data.put("byType", byType);
        data.put("byStatus", byStatus);
        // 兼容 board 消费方的常见命名；内容均为同一份无正文计数/摘要。
        data.put("typeCounts", byType);
        data.put("statusCounts", byStatus);
        data.put("recent", recent);
        data.put("openClaimedRecent", recent);
        return GmTicketToolSupport.success(NAME, data);
    }
}
