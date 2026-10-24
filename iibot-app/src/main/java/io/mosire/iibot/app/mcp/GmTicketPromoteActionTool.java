package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.ticket.TicketActionPromotionService;
import io.mosire.iibot.taskflow.ticket.TicketStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 将 ACTION_DRAFT ticket 晋升为 ActionPool action；只返回 ID/状态/revision。 */
public final class GmTicketPromoteActionTool implements AgentTool {

    public static final String NAME = "tf_ticket_promote_action";

    private static final Set<String> ROLES = Set.of("supervisor");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(Map.of(
            TicketStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY,
            ActionPoolStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY));
    private static final ResourceId TICKET_RESOURCE = ResourceId.of(
            TicketStore.RESOURCE_NAMESPACE, TicketStore.RESOURCE_NAMESPACE);
    private static final ResourceId ACTION_RESOURCE = ResourceId.of(
            ActionPoolStore.RESOURCE_NAMESPACE, ActionPoolStore.RESOURCE_NAMESPACE);
    private static final ToolSpec SPEC =
            ToolSpec.level(AccessToken.DEFAULT, true, false);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "把 ACTION_DRAFT 工单晋升为 ActionPool QUEUED action。",
            "properties", Map.of(
                    "ticketId", Map.of("type", "string", "description", "ACTION_DRAFT 工单 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0,
                            "description", "工单当前 revision"),
                    "idempotencyKey", Map.of("type", "string", "description", "幂等键")),
            "required", List.of("ticketId", "expectedRevision", "idempotencyKey"),
            "additionalProperties", false);

    private final TicketActionPromotionService promotionService;
    private final Actor supervisor;

    public GmTicketPromoteActionTool(TicketActionPromotionService promotionService,
                                     Actor supervisor) {
        this.promotionService = Objects.requireNonNull(promotionService,
                "promotionService");
        Objects.requireNonNull(supervisor, "supervisor");
        if (supervisor.role() != ActorRole.SUPERVISOR) {
            throw new IllegalArgumentException("Ticket promote actor 必须是 SUPERVISOR");
        }
        this.supervisor = supervisor;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "晋升 ACTION_DRAFT 工单为行动池行动；回执不含正文。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Set<String> roles() {
        return ROLES;
    }

    @Override
    public ResourceManifest resources() {
        return RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            context.resources().require(Operation.WRITE, TICKET_RESOURCE);
            context.resources().require(Operation.WRITE, ACTION_RESOURCE);
            Map<String, Object> args = context.arguments() == null
                    ? Map.of() : context.arguments();
            String ticketId = GmTicketToolSupport.requiredText(args, "ticketId",
                    GmTicketToolSupport.MAX_ID_CHARS);
            long expectedRevision = GmTicketToolSupport.requiredNonNegativeLong(
                    args, "expectedRevision");
            String key = GmTicketToolSupport.requiredText(args, "idempotencyKey",
                    GmTicketToolSupport.MAX_KEY_CHARS);
            TicketActionPromotionService.PromotionResult result = promotionService.promote(
                    supervisor, ticketId, expectedRevision, key);
            Map<String, Object> data = new LinkedHashMap<>();
            if (result.actionId() != null) {
                data.put("actionId", result.actionId());
            }
            data.put("status", result.status());
            data.put("revision", result.revision());
            if (!result.ok()) {
                return GmTicketToolSupport.failure(NAME, result.code(),
                        "工单行动晋升失败", data);
            }
            return GmTicketToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmTicketToolSupport.InvalidArgumentsException invalid) {
            return GmTicketToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return GmTicketToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage() == null ? "晋升参数非法" : invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmTicketToolSupport.failure(NAME, "PROMOTION_UNAVAILABLE",
                    "行动晋升服务暂不可用（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmTicketToolSupport.safeWriteLedgerArgs(context);
    }
}
