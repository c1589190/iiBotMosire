package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ticket.BatchReplyItem;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 批量回复；整批由 TicketService 原子处理。 */
public final class GmTicketBatchReplyTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_batch_reply";

    private static final Map<String, Object> ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "ticketId", Map.of("type", "string", "description", "工单 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0,
                            "description", "工单当前 revision"),
                    "replyBody", Map.of("type", "string", "description", "回复正文")),
            "required", List.of("ticketId", "expectedRevision", "replyBody"),
            "additionalProperties", false);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "原子批量回复多条工单；任一条失败整批不落盘。",
            "properties", Map.of(
                    "items", Map.of("type", "array", "items", ITEM_SCHEMA,
                            "maxItems", GmTicketToolSupport.MAX_BATCH_ITEMS,
                            "description", "批量回复条目，最多 100 条"),
                    "idempotencyKey", Map.of("type", "string", "description", "幂等键")),
            "required", List.of("items", "idempotencyKey"),
            "additionalProperties", false);

    private final TicketService ticketService;
    private final Actor supervisor;

    public GmTicketBatchReplyTool(TicketService ticketService, Actor supervisor) {
        super(NAME, "原子批量回复工单；任一条版本/状态非法则整批零变更。",
                SCHEMA, Operation.WRITE, GmTicketToolSupport.writeSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
        this.supervisor = GmTicketClaimTool.requireSupervisor(supervisor);
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        List<Map<String, Object>> rawItems = GmTicketToolSupport.requiredObjectList(
                arguments, "items");
        String key = GmTicketToolSupport.requiredText(arguments, "idempotencyKey",
                GmTicketToolSupport.MAX_KEY_CHARS);
        List<BatchReplyItem> items = new ArrayList<>(rawItems.size());
        List<String> ticketIds = new ArrayList<>(rawItems.size());
        for (Map<String, Object> raw : rawItems) {
            String ticketId = GmTicketToolSupport.requiredText(raw, "ticketId",
                    GmTicketToolSupport.MAX_ID_CHARS);
            long expectedRevision = GmTicketToolSupport.requiredNonNegativeLong(
                    raw, "expectedRevision");
            String replyBody = GmTicketToolSupport.requiredContent(raw, "replyBody",
                    GmTicketToolSupport.MAX_BODY_CHARS);
            ticketIds.add(ticketId);
            items.add(new BatchReplyItem(ticketId, expectedRevision, replyBody));
        }
        TicketCommandResult result = ticketService.batchReply(supervisor, items, key);
        if (!result.ok()) {
            return GmTicketToolSupport.commandFailure(NAME, result);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketIds", List.copyOf(ticketIds));
        data.put("count", ticketIds.size());
        data.put("status", "RESOLVED");
        data.put("revision", result.revision());
        return GmTicketToolSupport.success(NAME, data);
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmTicketToolSupport.safeWriteLedgerArgs(context);
    }
}
