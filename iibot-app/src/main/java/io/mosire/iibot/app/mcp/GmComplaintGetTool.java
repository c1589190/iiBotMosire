package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 只读读取任意投诉正文与 GM 回应。 */
public final class GmComplaintGetTool implements AgentTool {

    public static final String NAME = "tf_complaint_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按 complaintId 读取投诉正文与 GM 回应；只读。",
            "properties", Map.of(
                    "complaintId", Map.of("type", "string", "description", "投诉 ID")),
            "required", List.of("complaintId"),
            "additionalProperties", false);

    private final ComplaintService complaintService;

    public GmComplaintGetTool(ComplaintService complaintService) {
        this.complaintService = Objects.requireNonNull(complaintService, "complaintService");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读读取投诉正文与 GM 回应正文；GM 可读取全部投诉。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.DEFAULT;
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
            GmComplaintToolSupport.requireRead(context);
            String complaintId = GmComplaintToolSupport.requiredText(
                    context.arguments(), "complaintId",
                    GmComplaintToolSupport.MAX_COMPLAINT_ID_CHARS);
            Complaint complaint = complaintService.getForGm(complaintId).orElse(null);
            if (complaint == null) {
                return GmComplaintToolSupport.failure(NAME, "COMPLAINT_NOT_FOUND",
                        "投诉不存在");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("complaint", GmComplaintToolSupport.full(complaint));
            return GmComplaintToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmComplaintToolSupport.InvalidArgumentsException invalid) {
            return GmComplaintToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmComplaintToolSupport.failure(NAME, "COMPLAINT_UNAVAILABLE",
                    "投诉读取失败（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return Map.of();
    }
}
