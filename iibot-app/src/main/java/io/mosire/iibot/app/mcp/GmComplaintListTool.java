package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** GM 只读投诉摘要列表；不返回投诉正文与回应正文。 */
public final class GmComplaintListTool implements AgentTool {

    public static final String NAME = "tf_complaint_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "列出全部玩家投诉摘要，可按 status 过滤；不返回 body/response。",
            "properties", Map.of(
                    "status", Map.of("type", "string",
                            "enum", List.of("SUBMITTED", "RESPONDED"),
                            "description", "可选状态过滤")),
            "required", List.of(),
            "additionalProperties", false);

    private final ComplaintService complaintService;

    public GmComplaintListTool(ComplaintService complaintService) {
        this.complaintService = Objects.requireNonNull(complaintService, "complaintService");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读列出全部投诉摘要，可按状态过滤；不返回投诉正文与 GM 回应正文。";
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
            String statusText = GmComplaintToolSupport.optionalText(
                    context.arguments(), "status", 64);
            ComplaintStatus status = statusText.isBlank() ? null : parseStatus(statusText);
            List<Complaint> complaints = complaintService.listForGm(status);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", complaints.size());
            data.put("status", status == null ? null : status.name());
            data.put("complaints", complaints.stream()
                    .map(GmComplaintToolSupport::summary).toList());
            return GmComplaintToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmComplaintToolSupport.InvalidArgumentsException invalid) {
            return GmComplaintToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmComplaintToolSupport.failure(NAME, "COMPLAINT_UNAVAILABLE",
                    "投诉列表读取失败（内部错误已脱敏）");
        }
    }

    private static ComplaintStatus parseStatus(String value) {
        try {
            return ComplaintStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new GmComplaintToolSupport.InvalidArgumentsException(
                    "status 必须是合法投诉状态");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return Map.of();
    }
}
