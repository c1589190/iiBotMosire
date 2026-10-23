package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GM 推演秘书的只读投诉工具。
 *
 * <p>GM aide 可以列出全部投诉并读取任意投诉的正文与 GM 回应正文，但工具面没有回应、关闭、
 * 删除、发送或写入口。工具固定声明 {@code gm-aide} 角色、
 * {@link ToolSpec#DEFAULT} 与 {@code complaint} 只读资源面；实际调用仍经过
 * {@link io.mosire.agentlib.tool.ToolCallAuthorizer}。投诉正文只在 read 工具返回给当前 GM
 * 推演会话，超长时截断并标记，且不包含任何物理路径、QQ 号或 token。</p>
 */
public final class GmAideComplaintTools {

    public static final String LIST = "gm_complaints";
    public static final String READ = "gm_complaint_read";
    public static final String LIST_TOOL = LIST;
    public static final String READ_TOOL = READ;

    public static final String COMPLAINT_NOT_FOUND = "COMPLAINT_NOT_FOUND";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String COMPLAINTS_UNAVAILABLE = "COMPLAINTS_UNAVAILABLE";

    /** 单次读取回给模型的最大字符数；超过则截断并带固定标记。 */
    public static final int MAX_READ_RESULT_CHARS = 8 * 1024;
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    private static final Set<String> ROLES = Set.of(GmAideAgent.ROLE);
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            ComplaintStore.RESOURCE_NAMESPACE, ResourcePolicy.READ_ONLY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            ComplaintStore.RESOURCE_NAMESPACE, ComplaintStore.RESOURCE_NAMESPACE);

    private GmAideComplaintTools() {
    }

    /** 两个只读工具；顺序稳定。 */
    public static List<AgentTool> all(ComplaintService complaintService) {
        Objects.requireNonNull(complaintService, "complaintService");
        return List.of(list(complaintService), read(complaintService));
    }

    public static AgentTool list(ComplaintService complaintService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
        return new ReadTool(
                LIST,
                "列出全部玩家投诉摘要；不返回投诉正文与 GM 回应正文。",
                schema,
                context -> {
                    List<Complaint> complaints = complaintService.listForGm(null);
                    StringBuilder result = new StringBuilder("共 ")
                            .append(complaints.size()).append(" 条投诉：");
                    for (Complaint complaint : complaints) {
                        result.append("\n- complaintId=").append(complaint.complaintId())
                                .append(" playerId=").append(complaint.playerId())
                                .append(" title=").append(complaint.title())
                                .append(" status=").append(complaint.status())
                                .append(" revision=").append(complaint.revision())
                                .append(" respondedBy=").append(complaint.respondedBy())
                                .append(" deliveryState=").append(complaint.deliveryState());
                    }
                    return result.toString();
                });
    }

    public static AgentTool read(ComplaintService complaintService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "complaintId", Map.of("type", "string",
                                "description", "要读取的投诉 ID")),
                "required", List.of("complaintId"),
                "additionalProperties", false);
        return new ReadTool(
                READ,
                "按 complaintId 以 GM 权限只读读取投诉正文与 GM 回应；超长时截断并标记。",
                schema,
                context -> {
                    String complaintId = requiredText(context.arguments(), "complaintId");
                    Complaint complaint = complaintService.getForGm(complaintId).orElse(null);
                    if (complaint == null) {
                        throw new ToolFailure(COMPLAINT_NOT_FOUND, "投诉不存在");
                    }
                    StringBuilder result = new StringBuilder()
                            .append("complaintId: ").append(complaint.complaintId())
                            .append("\nplayerId: ").append(complaint.playerId())
                            .append("\ntitle: ").append(complaint.title())
                            .append("\nstatus: ").append(complaint.status())
                            .append("\nrevision: ").append(complaint.revision())
                            .append("\ncreatedAt: ").append(complaint.createdAt())
                            .append("\nupdatedAt: ").append(complaint.updatedAt())
                            .append("\nrespondedBy: ").append(complaint.respondedBy())
                            .append("\nrespondedAt: ").append(complaint.respondedAt())
                            .append("\ndeliveryState: ").append(complaint.deliveryState());
                    appendTruncated(result, "body", complaint.body());
                    if (complaint.responseBody() != null) {
                        appendTruncated(result, "responseBody", complaint.responseBody());
                    }
                    return result.toString();
                });
    }

    private static void appendTruncated(StringBuilder result, String label, String content) {
        boolean truncated = content.length() > MAX_READ_RESULT_CHARS;
        String rendered = truncated
                ? content.substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                : content;
        result.append('\n').append(label).append("Truncated: ").append(truncated)
                .append('\n').append(label).append(":\n").append(rendered);
    }

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolFailure(INVALID_ARGUMENTS, key + " 必须是非空字符串");
        }
        return text;
    }

    private static final class ToolFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private ToolFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 只读工具壳：metadata + 只读资源断言 + 稳定错误映射。 */
    private static final class ReadTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final java.util.function.Function<ToolContext, String> body;

        private ReadTool(String name, String description, Map<String, Object> schema,
                         java.util.function.Function<ToolContext, String> body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Map.copyOf(schema);
            this.body = Objects.requireNonNull(body, "body");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return schema;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.DEFAULT;
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
                context.resources().require(Operation.READ, RESOURCE_ID);
                return ToolResult.ok(body.apply(context));
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (ToolFailure failure) {
                return ToolResult.error(failure.code, failure.getMessage());
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(INVALID_ARGUMENTS,
                        invalid.getMessage() == null ? "工具参数非法" : invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error(COMPLAINTS_UNAVAILABLE,
                        "投诉服务暂不可用（内部错误已脱敏）");
            }
        }
    }
}
