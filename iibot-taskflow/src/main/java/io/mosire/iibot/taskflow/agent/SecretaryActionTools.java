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
import io.mosire.iibot.taskflow.action.ActionPoolException;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.action.PlayerAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家秘书的三个行动池工具：提交、列出自己的行动摘要、撤回自己尚未被选中的行动。
 *
 * <p>schema 中没有 playerId；身份只从宿主的 {@link ToolContext#config()} 读取。所有工具声明
 * {@code secretary} 角色与 {@code action-pool} 资源面，实际执行仍必须经过
 * {@link io.mosire.agentlib.tool.ToolCallAuthorizer}。submit 会写领域状态但非破坏性，
 * 因此标为 sensitive=true；list/withdraw 使用 {@link ToolSpec#DEFAULT}。</p>
 *
 * <p>工具回执与 ledgerArgs 都不包含行动正文。</p>
 */
public final class SecretaryActionTools {

    public static final String SUBMIT = "secretary_action_submit";
    public static final String LIST = "secretary_action_list";
    public static final String WITHDRAW = "secretary_action_withdraw";

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String ACTION_POOL_UNAVAILABLE = "ACTION_POOL_UNAVAILABLE";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            ActionPoolStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            ActionPoolStore.RESOURCE_NAMESPACE, ActionPoolStore.RESOURCE_NAMESPACE);
    private static final ToolSpec SUBMIT_SPEC =
            new ToolSpec(AccessToken.GUEST, true, false, false);

    private SecretaryActionTools() {
    }

    /** 三个工具；顺序稳定，便于会话装配与测试对账。 */
    public static List<AgentTool> all(ActionPoolService actionPoolService) {
        Objects.requireNonNull(actionPoolService, "actionPoolService");
        return List.of(submit(actionPoolService), list(actionPoolService),
                withdraw(actionPoolService));
    }

    public static AgentTool submit(ActionPoolService actionPoolService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string",
                                "description", "自然语言行动正文，1..16384 UTF-8 字节"),
                        "source", Map.of("type", "string",
                                "description", "行动来源说明，例如 private-chat"),
                        "idempotencyKey", Map.of("type", "string",
                                "description", "幂等键；同一玩家重复提交返回首次结果")),
                "required", List.of("text", "source", "idempotencyKey"),
                "additionalProperties", false);
        return new ActionTool(SUBMIT,
                "为当前秘书所属玩家提交一条待 GM 裁定的自然语言行动；随时可用，不要求回合开启。",
                schema, Operation.WRITE, SUBMIT_SPEC, context -> {
                    BoundPlayer bound = requirePlayer(context);
                    Map<String, Object> args = context.arguments();
                    PlayerAction action = actionPoolService.submit(bound.actor(),
                            requireString(args, "text"), requireString(args, "source"),
                            requireString(args, "idempotencyKey"));
                    return ToolResult.ok("行动已进入待裁定池。\nactionId: " + action.actionId()
                            + "\nstatus: " + action.status()
                            + "\nsource: " + action.source());
                }) {
            @Override
            public Map<String, Object> ledgerArgs(ToolContext context) {
                Map<String, Object> safe = new LinkedHashMap<>(context.arguments());
                safe.remove("text");
                return Map.copyOf(safe);
            }
        };
    }

    public static AgentTool list(ActionPoolService actionPoolService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
        return new ActionTool(LIST,
                "列出当前玩家自己的行动摘要；只返回 ID/状态/来源，不返回正文。",
                schema, Operation.READ, ToolSpec.DEFAULT, context -> {
                    BoundPlayer bound = requirePlayer(context);
                    List<PlayerAction> actions = actionPoolService.listForPlayer(
                            bound.playerId());
                    StringBuilder result = new StringBuilder("共 ")
                            .append(actions.size()).append(" 条行动：");
                    for (PlayerAction action : actions) {
                        result.append("\n- actionId=").append(action.actionId())
                                .append(" status=").append(action.status())
                                .append(" packageId=").append(action.packageId())
                                .append(" source=").append(action.source())
                                .append(" submittedAt=").append(action.createdAt());
                    }
                    return ToolResult.ok(result.toString());
                });
    }

    public static AgentTool withdraw(ActionPoolService actionPoolService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "actionId", Map.of("type", "string",
                                "description", "要撤回的行动 ID"),
                        "idempotencyKey", Map.of("type", "string",
                                "description", "撤回操作幂等键")),
                "required", List.of("actionId", "idempotencyKey"),
                "additionalProperties", false);
        return new ActionTool(WITHDRAW,
                "撤回当前玩家自己尚未被 GM 选中的 QUEUED 行动；SELECTED 行动不可撤回。",
                schema, Operation.WRITE, ToolSpec.DEFAULT, context -> {
                    BoundPlayer bound = requirePlayer(context);
                    Map<String, Object> args = context.arguments();
                    PlayerAction action = actionPoolService.withdraw(bound.actor(),
                            requireString(args, "actionId"),
                            requireString(args, "idempotencyKey"));
                    return ToolResult.ok("行动已撤回或本就处于撤回状态。\nactionId: "
                            + action.actionId() + "\nstatus: " + action.status());
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
    private static class ActionTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final ToolSpec spec;
        private final java.util.function.Function<ToolContext, ToolResult> body;

        private ActionTool(String name, String description, Map<String, Object> schema,
                           Operation operation, ToolSpec spec,
                           java.util.function.Function<ToolContext, ToolResult> body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Map.copyOf(schema);
            this.operation = Objects.requireNonNull(operation, "operation");
            this.spec = Objects.requireNonNull(spec, "spec");
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
            return spec;
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
            } catch (ActionPoolException failure) {
                return ToolResult.error(failure.code(), failure.getMessage());
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(INVALID_ARGUMENTS, invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error(ACTION_POOL_UNAVAILABLE,
                        "行动池暂不可用（内部错误已脱敏）");
            }
        }
    }
}
