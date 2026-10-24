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

/** GM 回复单条工单。 */
public final class GmTicketReplyTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_reply";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "回复 OPEN/CLAIMED 工单并置为 RESOLVED。",
            "properties", Map.of(
                    "ticketId", Map.of("type", "string", "description", "工单 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0,
                            "description", "工单当前 revision"),
                    "replyBody", Map.of("type", "string", "description", "回复正文"),
                    "idempotencyKey", Map.of("type", "string", "description", "幂等键")),
            "required", List.of("ticketId", "expectedRevision", "replyBody",
                    "idempotencyKey"),
            "additionalProperties", false);

    private final TicketService ticketService;
    private final Actor supervisor;

    public GmTicketReplyTool(TicketService ticketService, Actor supervisor) {
        super(NAME, "回复工单并置为 RESOLVED；回执不回显回复正文。",
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
        String replyBody = GmTicketToolSupport.requiredContent(arguments, "replyBody",
                GmTicketToolSupport.MAX_BODY_CHARS);
        String key = GmTicketToolSupport.requiredText(arguments, "idempotencyKey",
                GmTicketToolSupport.MAX_KEY_CHARS);
        TicketCommandResult result = ticketService.reply(supervisor, ticketId,
                expectedRevision, replyBody, key);
        if (!result.ok()) {
            return GmTicketToolSupport.commandFailure(NAME, result);
        }
        return GmTicketToolSupport.success(NAME,
                GmTicketToolSupport.commandData(result, "RESOLVED"));
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmTicketToolSupport.safeWriteLedgerArgs(context);
    }
}
