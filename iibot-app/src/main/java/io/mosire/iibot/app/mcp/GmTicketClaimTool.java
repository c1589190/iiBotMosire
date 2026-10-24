package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM claim 工单。 */
public final class GmTicketClaimTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_claim";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "claim 一条 OPEN 工单。",
            "properties", Map.of(
                    "ticketId", Map.of("type", "string", "description", "工单 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0,
                            "description", "工单当前 revision"),
                    "idempotencyKey", Map.of("type", "string", "description", "幂等键")),
            "required", List.of("ticketId", "expectedRevision", "idempotencyKey"),
            "additionalProperties", false);

    private final TicketService ticketService;
    private final Actor supervisor;

    public GmTicketClaimTool(TicketService ticketService, Actor supervisor) {
        super(NAME, "claim 工单；成功返回 revision。",
                SCHEMA, Operation.WRITE, GmTicketToolSupport.writeSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
        this.supervisor = requireSupervisor(supervisor);
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        String ticketId = GmTicketToolSupport.requiredText(arguments, "ticketId",
                GmTicketToolSupport.MAX_ID_CHARS);
        long expectedRevision = GmTicketToolSupport.requiredNonNegativeLong(
                arguments, "expectedRevision");
        String key = GmTicketToolSupport.requiredText(arguments, "idempotencyKey",
                GmTicketToolSupport.MAX_KEY_CHARS);
        TicketCommandResult result = ticketService.claim(supervisor, ticketId,
                expectedRevision, key);
        if (!result.ok()) {
            return GmTicketToolSupport.commandFailure(NAME, result);
        }
        return GmTicketToolSupport.success(NAME,
                GmTicketToolSupport.commandData(result, "CLAIMED"));
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmTicketToolSupport.safeWriteLedgerArgs(context);
    }

    static Actor requireSupervisor(Actor actor) {
        Objects.requireNonNull(actor, "supervisor");
        if (actor.role() != ActorRole.SUPERVISOR) {
            throw new IllegalArgumentException("GM ticket 工具 actor 必须是 SUPERVISOR");
        }
        return actor;
    }
}
