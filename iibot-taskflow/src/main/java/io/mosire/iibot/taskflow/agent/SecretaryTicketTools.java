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
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketReply;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketStore;
import io.mosire.iibot.taskflow.ticket.TicketType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 秘书侧统一工单工具：提交、列出自己的工单摘要、读取自己的工单全文。
 *
 * <p>schema 中没有 playerId；身份只来自宿主 config 中的已认证 PLAYER actor。三个工具声明
 * {@code secretary} 角色与 {@code ticket} 资源面，实际执行仍经 ToolCallAuthorizer。GM_NOTE
 * 不可由秘书提交，工单正文不进入 ledgerArgs。</p>
 */
public final class SecretaryTicketTools {

    public static final String SUBMIT = "secretary_ticket_submit";
    public static final String LIST = "secretary_ticket_list";
    public static final String READ = "secretary_ticket_read";

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String TICKET_UNAVAILABLE = "TICKET_UNAVAILABLE";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            TicketStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            TicketStore.RESOURCE_NAMESPACE, TicketStore.RESOURCE_NAMESPACE);
    private static final ToolSpec SENSITIVE_SPEC =
            ToolSpec.level(AccessToken.DEFAULT, true, false);

    private SecretaryTicketTools() {
    }

    public static List<AgentTool> all(TicketService ticketService) {
        Objects.requireNonNull(ticketService, "ticketService");
        return List.of(submit(ticketService), list(ticketService), read(ticketService));
    }

    public static AgentTool submit(TicketService ticketService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "type", Map.of("type", "string",
                                "enum", List.of("SETTING_QUERY", "ACTION_DRAFT", "COMPLAINT"),
                                "description", "工单类型"),
                        "title", Map.of("type", "string", "description", "工单标题"),
                        "body", Map.of("type", "string", "description", "工单正文"),
                        "idempotencyKey", Map.of("type", "string",
                                "description", "幂等键；同一键返回首次成功回执")),
                "required", List.of("type", "title", "body", "idempotencyKey"),
                "additionalProperties", false);
        return new TicketTool(SUBMIT,
                "代表当前秘书所属玩家提交统一工单；无需回合，不接受 playerId。",
                schema, Operation.WRITE, context -> {
                    BoundPlayer bound = requirePlayer(context);
                    Map<String, Object> args = context.arguments();
                    TicketType type = parseSubmitType(requireString(args, "type"));
                    String title = requireString(args, "title");
                    String body = requireString(args, "body");
                    String key = requireString(args, "idempotencyKey");
                    TicketCommandResult result = ticketService.submit(
                            bound.actor(), type, title, body, key);
                    if (!result.ok()) {
                        return ToolResult.error(result.code(), result.message());
                    }
                    return ToolResult.ok("工单已提交。\nticketId: " + result.ticketId()
                            + "\ntype: " + type.name()
                            + "\nstatus: OPEN"
                            + "\nrevision: " + result.revision());
                }) {
            @Override
            public Map<String, Object> ledgerArgs(ToolContext context) {
                Map<String, Object> safe = new LinkedHashMap<>(context.arguments());
                safe.remove("body");
                return Map.copyOf(safe);
            }
        };
    }

    public static AgentTool list(TicketService ticketService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "type", Map.of("type", "string",
                                "description", "可选工单类型过滤"),
                        "status", Map.of("type", "string",
                                "description", "可选状态过滤")),
                "additionalProperties", false);
        return new TicketTool(LIST,
                "列出当前秘书所属玩家的工单摘要；不返回正文，也不接受 playerId。",
                schema, Operation.READ, context -> {
                    BoundPlayer bound = requirePlayer(context);
                    Map<String, Object> args = context.arguments();
                    TicketType type = parseOptionalType(args.get("type"));
                    TicketStatus status = parseOptionalStatus(args.get("status"));
                    List<Ticket> tickets = ticketService.listForSecretary(
                            bound.playerId(), type, status);
                    StringBuilder output = new StringBuilder("共 ")
                            .append(tickets.size()).append(" 条工单：");
                    for (Ticket ticket : tickets) {
                        output.append("\n- ticketId=").append(ticket.ticketId())
                                .append(" type=").append(ticket.type())
                                .append(" status=").append(ticket.status())
                                .append(" revision=").append(ticket.revision())
                                .append(" title=").append(ticket.title())
                                .append(" replies=").append(ticket.replies().size())
                                .append(" updatedAt=").append(ticket.updatedAt());
                    }
                    return ToolResult.ok(output.toString());
                });
    }

    public static AgentTool read(TicketService ticketService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "ticketId", Map.of("type", "string",
                                "description", "要读取的工单 ID")),
                "required", List.of("ticketId"),
                "additionalProperties", false);
        return new TicketTool(READ,
                "读取当前秘书所属玩家自己的工单全文与回复；越权与不存在返回同一稳定错误。",
                schema, Operation.READ, context -> {
                    BoundPlayer bound = requirePlayer(context);
                    String ticketId = requireString(context.arguments(), "ticketId");
                    Optional<Ticket> found = ticketService.getForSecretary(
                            bound.playerId(), ticketId);
                    if (found.isEmpty()) {
                        return ToolResult.error(TicketService.TICKET_NOT_FOUND,
                                "工单不存在或当前秘书无权访问");
                    }
                    Ticket ticket = found.get();
                    StringBuilder output = new StringBuilder()
                            .append("ticketId: ").append(ticket.ticketId())
                            .append("\ntype: ").append(ticket.type())
                            .append("\nstatus: ").append(ticket.status())
                            .append("\nrevision: ").append(ticket.revision())
                            .append("\ncreatedAt: ").append(ticket.createdAt())
                            .append("\nupdatedAt: ").append(ticket.updatedAt())
                            .append("\ntitle: ").append(ticket.title())
                            .append("\nbody:\n").append(ticket.body());
                    for (TicketReply reply : ticket.replies()) {
                        output.append("\n---\nreplyId: ").append(reply.replyId())
                                .append("\nbyActorId: ").append(reply.byActorId())
                                .append("\nat: ").append(reply.at())
                                .append("\nbody:\n").append(reply.body());
                    }
                    if (ticket.closedBy() != null) {
                        output.append("\nclosedBy: ").append(ticket.closedBy())
                                .append("\ncloseReason: ").append(ticket.closeReason());
                    }
                    return ToolResult.ok(output.toString());
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
        Object value = args == null ? null : args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return text;
    }

    private static TicketType parseSubmitType(String value) {
        TicketType type;
        try {
            type = TicketType.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("工单类型非法");
        }
        if (type == TicketType.GM_NOTE) {
            throw new IllegalArgumentException("秘书不能提交 GM_NOTE");
        }
        return type;
    }

    private static TicketType parseOptionalType(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("type 提供时必须是非空字符串");
        }
        try {
            return TicketType.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("工单类型非法");
        }
    }

    private static TicketStatus parseOptionalStatus(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("status 提供时必须是非空字符串");
        }
        try {
            return TicketStatus.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("工单状态非法");
        }
    }

    private record BoundPlayer(String playerId, Actor actor) {
        private BoundPlayer {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(actor, "actor");
        }
    }

    private static class TicketTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final java.util.function.Function<ToolContext, ToolResult> body;

        private TicketTool(String name, String description, Map<String, Object> schema,
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
                return ToolResult.error(TICKET_UNAVAILABLE,
                        "工单服务暂不可用（内部错误已脱敏）");
            }
        }
    }
}
