package io.mosire.iibot.taskflow.agent;

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
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 玩家秘书的投诉工具：提交、列出自己的投诉、读取自己的投诉（含 GM 回应）。
 *
 * <p>身份只从宿主注入的 {@link ToolContext#config()} 读取；三个工具的 JSON Schema 都没有
 * {@code playerId} 参数，也不存在回应/关闭/读取他人投诉的工具。所有工具声明
 * {@code secretary} 角色与 {@code complaint} 资源面，实际执行仍经过
 * {@link io.mosire.agentlib.tool.ToolCallAuthorizer}。</p>
 *
 * <p>投诉提交不依赖当前回合，因此这里的身份绑定只要求 ACTOR 与 PLAYER_ID，不要求 turnId。
 * 列表只返回摘要；读取工具为回答当前秘书自己的投诉会返回正文与 GM 回应正文，但二者不进入
 * 领域审计摘要。</p>
 */
public final class SecretaryComplaintTools {

    public static final String SUBMIT = "secretary_complaint_submit";
    public static final String LIST = "secretary_complaint_list";
    public static final String READ = "secretary_complaint_read";

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String COMPLAINT_UNAVAILABLE = "COMPLAINT_UNAVAILABLE";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            ComplaintStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            ComplaintStore.RESOURCE_NAMESPACE, ComplaintStore.RESOURCE_NAMESPACE);
    private static final ToolSpec SENSITIVE_SPEC =
            ToolSpec.level(AccessToken.DEFAULT, true, false);

    private SecretaryComplaintTools() {
    }

    /** 三个工具；顺序稳定，便于会话装配与测试对账。 */
    public static List<AgentTool> all(ComplaintService complaintService) {
        Objects.requireNonNull(complaintService, "complaintService");
        return List.of(submit(complaintService), list(complaintService), read(complaintService));
    }

    public static AgentTool submit(ComplaintService complaintService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "投诉标题"),
                        "body", Map.of("type", "string", "description", "投诉正文"),
                        "idempotencyKey", Map.of("type", "string",
                                "description", "幂等键；同一键重复调用返回首次成功回执")),
                "required", List.of("title", "body", "idempotencyKey"),
                "additionalProperties", false);
        return new ComplaintTool(
                SUBMIT,
                "代表当前秘书所属玩家提交一条投诉；无需等待回合，也不接受 playerId。",
                schema,
                Operation.WRITE,
                context -> {
                    BoundPlayer bound = requirePlayer(context);
                    Map<String, Object> args = context.arguments();
                    String title = requireString(args, "title");
                    String body = requireString(args, "body");
                    String idempotencyKey = requireString(args, "idempotencyKey");
                    ComplaintCommandResult result = complaintService.submit(
                            bound.actor(), title, body, idempotencyKey);
                    if (!result.ok()) {
                        return ToolResult.error(result.code(), result.message());
                    }
                    return ToolResult.ok("投诉已提交。\ncomplaintId: " + result.complaintId()
                            + "\nstatus: SUBMITTED"
                            + "\nrevision: " + result.revision());
                }) {
            @Override
            public Map<String, Object> ledgerArgs(ToolContext context) {
                // 投诉正文不进入可观察的工具审计参数；工具回执也不包含正文。
                Map<String, Object> safe = new LinkedHashMap<>(context.arguments());
                safe.remove("body");
                return Map.copyOf(safe);
            }
        };
    }

    public static AgentTool list(ComplaintService complaintService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
        return new ComplaintTool(
                LIST,
                "列出当前秘书所属玩家的投诉摘要；不返回正文，也不接受 playerId。",
                schema,
                Operation.READ,
                context -> {
                    BoundPlayer bound = requirePlayer(context);
                    List<Complaint> complaints =
                            complaintService.listForSecretary(bound.playerId());
                    StringBuilder result = new StringBuilder("共 ")
                            .append(complaints.size()).append(" 条投诉：");
                    for (Complaint complaint : complaints) {
                        result.append("\n- complaintId=").append(complaint.complaintId())
                                .append(" title=").append(complaint.title())
                                .append(" status=").append(complaint.status())
                                .append(" revision=").append(complaint.revision())
                                .append(" respondedAt=").append(complaint.respondedAt());
                    }
                    return ToolResult.ok(result.toString());
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
        return new ComplaintTool(
                READ,
                "读取当前秘书所属玩家自己的投诉与 GM 回应；越权与不存在返回同一稳定错误。",
                schema,
                Operation.READ,
                context -> {
                    BoundPlayer bound = requirePlayer(context);
                    String complaintId = requireString(context.arguments(), "complaintId");
                    Optional<Complaint> found = complaintService.getForSecretary(
                            bound.playerId(), complaintId);
                    if (found.isEmpty()) {
                        return ToolResult.error(NOT_FOUND, "投诉不存在或当前秘书无权访问");
                    }
                    Complaint complaint = found.get();
                    StringBuilder result = new StringBuilder()
                            .append("complaintId: ").append(complaint.complaintId())
                            .append("\nstatus: ").append(complaint.status())
                            .append("\nrevision: ").append(complaint.revision())
                            .append("\ncreatedAt: ").append(complaint.createdAt())
                            .append("\nupdatedAt: ").append(complaint.updatedAt())
                            .append("\ntitle: ").append(complaint.title())
                            .append("\nbody:\n").append(complaint.body());
                    if (complaint.responseBody() != null) {
                        result.append("\nrespondedBy: ").append(complaint.respondedBy())
                                .append("\nrespondedAt: ").append(complaint.respondedAt())
                                .append("\nresponseBody:\n").append(complaint.responseBody());
                    }
                    return ToolResult.ok(result.toString());
                });
    }

    private static BoundPlayer requirePlayer(ToolContext context) {
        Objects.requireNonNull(context, "context");
        Object actorValue = context.config().get(SecretaryToolContext.ACTOR);
        Object playerIdValue = context.config().get(SecretaryToolContext.PLAYER_ID);
        if (!(actorValue instanceof Actor actor)) {
            throw new IllegalArgumentException("宿主未绑定合法 actor");
        }
        if (!(playerIdValue instanceof String playerId) || playerId.isBlank()) {
            throw new IllegalArgumentException("宿主未绑定合法 playerId");
        }
        if (actor.role() != ActorRole.PLAYER || !actor.actorId().equals(playerId)) {
            throw new IllegalArgumentException("绑定的 actor 与 playerId 不一致或不是玩家");
        }
        return new BoundPlayer(playerId, actor);
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return text;
    }

    private record BoundPlayer(String playerId, Actor actor) {
        private BoundPlayer {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(actor, "actor");
        }
    }

    /** 统一工具壳：metadata + 资源断言 + 稳定错误映射。 */
    private static class ComplaintTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final java.util.function.Function<ToolContext, ToolResult> body;

        private ComplaintTool(String name, String description, Map<String, Object> schema,
                              Operation operation,
                              java.util.function.Function<ToolContext, ToolResult> body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Map.copyOf(schema);
            this.operation = Objects.requireNonNull(operation, "operation");
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
            return SENSITIVE_SPEC;
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
                context.resources().require(operation, RESOURCE_ID);
                return body.apply(context);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(INVALID_ARGUMENTS, invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error(COMPLAINT_UNAVAILABLE,
                        "投诉服务暂不可用（内部错误已脱敏）");
            }
        }
    }
}
