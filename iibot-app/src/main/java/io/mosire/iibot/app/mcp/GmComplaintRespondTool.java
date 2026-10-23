package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 回应投诉；只调用 ComplaintService.respond，QQ 投递由宿主 ComplaintDistributor 编排。 */
public final class GmComplaintRespondTool implements AgentTool {

    public static final String NAME = "tf_complaint_respond";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "GM 回应投诉；只提交领域响应，不直接调用 QQ 发送工具。",
            "properties", Map.of(
                    "complaintId", Map.of("type", "string", "description", "投诉 ID"),
                    "response", Map.of("type", "string", "description", "GM 回应正文"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 1,
                            "description", "目标投诉当前 revision"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键重复调用返回首次成功回执")),
            "required", List.of("complaintId", "response", "expectedRevision",
                    "idempotencyKey"),
            "additionalProperties", false);

    private final ComplaintService complaintService;
    private final Actor supervisor;

    public GmComplaintRespondTool(ComplaintService complaintService, Actor supervisor) {
        this.complaintService = Objects.requireNonNull(complaintService, "complaintService");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "GM 回应一条投诉；回应后由宿主唯一外发编排发送到投诉玩家私聊。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public java.util.Set<String> roles() {
        return GmComplaintToolSupport.ROLES;
    }

    @Override
    public io.mosire.agentlib.permission.ResourceManifest resources() {
        return GmComplaintToolSupport.RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmComplaintToolSupport.requireWrite(context);
            Map<String, Object> args = context.arguments();
            String complaintId = GmComplaintToolSupport.requiredText(
                    args, "complaintId", GmComplaintToolSupport.MAX_COMPLAINT_ID_CHARS);
            String response = GmComplaintToolSupport.requiredText(
                    args, "response", ComplaintService.MAX_RESPONSE_BYTES);
            long expectedRevision = GmComplaintToolSupport.requiredPositiveLong(
                    args, "expectedRevision");
            String idempotencyKey = GmComplaintToolSupport.requiredText(
                    args, "idempotencyKey",
                    GmComplaintToolSupport.MAX_IDEMPOTENCY_KEY_CHARS);
            ComplaintCommandResult result = complaintService.respond(
                    supervisor, complaintId, expectedRevision, response, idempotencyKey);
            Map<String, Object> extra = new LinkedHashMap<>();
            complaintService.getForGm(complaintId).ifPresent(complaint -> {
                extra.put("status", complaint.status().name());
                extra.put("deliveryState", complaint.deliveryState() == null
                        ? null : complaint.deliveryState().name());
            });
            return GmComplaintToolSupport.fromCommand(NAME, result, extra);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmComplaintToolSupport.InvalidArgumentsException invalid) {
            return GmComplaintToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmComplaintToolSupport.failure(NAME, "COMPLAINT_UNAVAILABLE",
                    "投诉回应失败（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String key : List.of("complaintId", "expectedRevision", "idempotencyKey")) {
                if (args.containsKey(key)) {
                    safe.put(key, args.get(key));
                }
            }
        }
        return Map.copyOf(safe);
    }
}
