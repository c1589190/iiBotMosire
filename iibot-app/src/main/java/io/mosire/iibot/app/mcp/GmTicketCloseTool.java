package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 关闭工单；reason 作为 closeReason 写入领域快照。 */
public final class GmTicketCloseTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_close";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "关闭 OPEN/CLAIMED/RESOLVED 工单，reason 写入 closeReason。",
            "properties", Map.of(
                    "ticketId", Map.of("type", "string", "description", "工单 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0,
                            "description", "工单当前 revision"),
                    "idempotencyKey", Map.of("type", "string", "description", "幂等键"),
                    "reason", Map.of("type", "string", "description", "关闭原因/审计原因")),
            "required", List.of("ticketId", "expectedRevision", "idempotencyKey", "reason"),
            "additionalProperties", false);

    private final TicketService ticketService;
    private final Actor supervisor;

    public GmTicketCloseTool(TicketService ticketService, Actor supervisor) {
        super(NAME, "关闭工单；成功返回 CLOSED revision。",
                SCHEMA, Operation.WRITE, GmTicketToolSupport.writeSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
        this.supervisor = GmTicketClaimTool.requireSupervisor(supervisor);
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        String ticketId = GmTicketToolSupport.requiredText(arguments, "ticketId",
                GmTicketToolSupport.MAX_ID_CHARS);
        long expectedRevision = GmTicketToolSupport.requiredNonNegativeLong(
                arguments, "expectedRevision");
        String key = GmTicketToolSupport.requiredText(arguments, "idempotencyKey",
                GmTicketToolSupport.MAX_KEY_CHARS);
        String reason = GmTicketToolSupport.requiredText(arguments, "reason",
                GmTicketToolSupport.MAX_REASON_CHARS);
        TicketCommandResult result = ticketService.close(supervisor, ticketId,
                expectedRevision, reason, key);
        if (!result.ok()) {
            return GmTicketToolSupport.commandFailure(NAME, result);
        }
        return GmTicketToolSupport.success(NAME,
                GmTicketToolSupport.commandData(result, "CLOSED"));
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmTicketToolSupport.safeWriteLedgerArgs(context);
    }
}
