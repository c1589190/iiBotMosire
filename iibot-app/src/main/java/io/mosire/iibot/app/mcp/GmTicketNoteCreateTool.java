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

/** GM 创建 GM_NOTE；不要求 expectedRevision。 */
public final class GmTicketNoteCreateTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_note_create";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "创建一条 GM_NOTE 工单。",
            "properties", Map.of(
                    "title", Map.of("type", "string", "description", "备注标题"),
                    "body", Map.of("type", "string", "description", "备注正文"),
                    "idempotencyKey", Map.of("type", "string", "description", "幂等键")),
            "required", List.of("title", "body", "idempotencyKey"),
            "additionalProperties", false);

    private final TicketService ticketService;
    private final Actor supervisor;

    public GmTicketNoteCreateTool(TicketService ticketService, Actor supervisor) {
        super(NAME, "创建 GM_NOTE；回执不回显正文。",
                SCHEMA, Operation.WRITE, GmTicketToolSupport.writeSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
        this.supervisor = GmTicketClaimTool.requireSupervisor(supervisor);
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        String title = GmTicketToolSupport.requiredText(arguments, "title",
                GmTicketToolSupport.MAX_TITLE_CHARS);
        String body = GmTicketToolSupport.requiredContent(arguments, "body",
                GmTicketToolSupport.MAX_BODY_CHARS);
        String key = GmTicketToolSupport.requiredText(arguments, "idempotencyKey",
                GmTicketToolSupport.MAX_KEY_CHARS);
        TicketCommandResult result = ticketService.createGmNote(supervisor, title, body, key);
        if (!result.ok()) {
            return GmTicketToolSupport.commandFailure(NAME, result);
        }
        return GmTicketToolSupport.success(NAME,
                GmTicketToolSupport.commandData(result, "OPEN"));
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmTicketToolSupport.safeWriteLedgerArgs(context);
    }
}
