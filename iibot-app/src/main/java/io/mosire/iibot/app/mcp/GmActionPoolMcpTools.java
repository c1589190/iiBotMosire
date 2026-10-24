package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolException;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.decision.ActionPackageService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** GM 行动池三个 supervisor MCP 工具；列表默认只给摘要，get 才返回正文。 */
public final class GmActionPoolMcpTools {

    public static final String LIST = "tf_action_pool_list";
    public static final String GET = "tf_action_pool_get";
    public static final String IGNORE = "tf_action_pool_ignore";

    public static final List<String> NAMES = List.of(LIST, GET, IGNORE);

    private GmActionPoolMcpTools() {
    }

    /** 兼容无 package 服务的旧装配。 */
    public static List<AgentTool> all(ActionPoolService actionPoolService) {
        return all(actionPoolService, null, null);
    }

    /** 兼容二元装配；package 服务非空时使用宿主默认 supervisor 身份。 */
    public static List<AgentTool> all(ActionPoolService actionPoolService,
                                      ActionPackageService actionPackageService) {
        return all(actionPoolService, actionPackageService, null);
    }

    /** package 服务非空时 ignore 与 canonical 包写共享同一 ActionPackageService 监视器。 */
    public static List<AgentTool> all(ActionPoolService actionPoolService,
                                      ActionPackageService actionPackageService,
                                      Actor supervisor) {
        Objects.requireNonNull(actionPoolService, "actionPoolService");
        return List.of(list(actionPoolService), get(actionPoolService),
                ignore(actionPoolService, actionPackageService, supervisor));
    }

    public static AgentTool list(ActionPoolService actionPoolService) {
        Objects.requireNonNull(actionPoolService, "actionPoolService");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "列出 GM 可见行动摘要；可选按状态过滤，永不返回行动正文。",
                "properties", Map.of(
                        "status", Map.of("type", "string",
                                "enum", List.of("QUEUED", "SELECTED", "COMMITTED", "WITHDRAWN"),
                                "description", "可选行动状态过滤")),
                "required", List.of(),
                "additionalProperties", false);
        return GmDecisionToolSupport.readTool(LIST,
                "列出全部行动摘要（actionId/playerId/status/packageId/时间/source/textLength），"
                        + "不含正文；可按 status 过滤。",
                schema, ActionPoolStore.RESOURCE_NAMESPACE, context -> {
                    String statusText = GmDecisionToolSupport.optionalText(
                            context.arguments(), "status", 32);
                    ActionStatus status = statusText.isBlank() ? null : parseStatus(statusText);
                    List<PlayerAction> actions = actionPoolService.listForGm(status);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", actions.size());
                    data.put("status", status == null ? null : status.name());
                    data.put("actions", actions.stream()
                            .map(GmActionPoolMcpTools::summary).toList());
                    return GmDecisionToolSupport.success(LIST, data);
                });
    }

    public static AgentTool get(ActionPoolService actionPoolService) {
        Objects.requireNonNull(actionPoolService, "actionPoolService");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "GM 按 actionId 读取一条行动，包含自然语言正文。",
                "properties", Map.of(
                        "actionId", Map.of("type", "string", "minLength", 1,
                                "description", "行动 ID")),
                "required", List.of("actionId"),
                "additionalProperties", false);
        return GmDecisionToolSupport.readTool(GET,
                "读取一条行动的完整字段（GM 可见正文）。",
                schema, ActionPoolStore.RESOURCE_NAMESPACE, context -> {
                    String actionId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "actionId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    PlayerAction action = actionPoolService.getForGm(actionId)
                            .orElseThrow(() -> new ActionPoolException(
                                    ActionPoolException.NOT_FOUND,
                                    "行动不存在: " + actionId));
                    return GmDecisionToolSupport.success(GET, full(action));
                });
    }

    /** 兼容无 package 服务的旧装配。 */
    public static AgentTool ignore(ActionPoolService actionPoolService) {
        return ignore(actionPoolService, null, null);
    }

    /** 兼容二元装配；package 服务非空时使用宿主默认 supervisor 身份。 */
    public static AgentTool ignore(ActionPoolService actionPoolService,
                                   ActionPackageService actionPackageService) {
        return ignore(actionPoolService, actionPackageService, null);
    }

    public static AgentTool ignore(ActionPoolService actionPoolService,
                                   ActionPackageService actionPackageService,
                                   Actor supervisor) {
        Objects.requireNonNull(actionPoolService, "actionPoolService");
        Actor effectiveSupervisor = supervisor == null
                ? new Actor("external-supervisor", ActorRole.SUPERVISOR) : supervisor;
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "GM 忽略一条 QUEUED 行动（WITHDRAWN）；不能取消 SELECTED/COMMITTED。",
                "properties", Map.of(
                        "actionId", Map.of("type", "string", "minLength", 1,
                                "description", "要忽略的行动 ID"),
                        "idempotencyKey", Map.of("type", "string", "minLength", 1,
                                "description", "忽略操作幂等键"),
                        "reason", Map.of("type", "string", "minLength", 1,
                                "description", "忽略原因摘要，不写入行动正文")),
                "required", List.of("actionId", "idempotencyKey", "reason"),
                "additionalProperties", false);
        return GmDecisionToolSupport.writeTool(IGNORE,
                "把 GM 选中的 QUEUED 行动标为 WITHDRAWN；SELECTED/COMMITTED 行动拒绝取消。",
                schema, ActionPoolStore.RESOURCE_NAMESPACE, context -> {
                    String actionId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "actionId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    String key = GmDecisionToolSupport.requiredText(
                            context.arguments(), "idempotencyKey",
                            GmDecisionToolSupport.MAX_KEY_CHARS);
                    String reason = GmDecisionToolSupport.requiredText(
                            context.arguments(), "reason",
                            GmDecisionToolSupport.MAX_NOTE_CHARS);
                    PlayerAction ignored;
                    if (actionPackageService != null) {
                        // 在 ActionPackageService 同一监视器内完成 canonical owner 检查与
                        // withdraw，防止 ignore 与 create/append/commit 发生 TOCTOU。
                        ignored = actionPackageService.ignoreAction(
                                effectiveSupervisor, actionId, key, reason);
                    } else {
                        PlayerAction current = actionPoolService.getForGm(actionId)
                                .orElseThrow(() -> new ActionPoolException(
                                        ActionPoolException.NOT_FOUND,
                                        "行动不存在: " + actionId));
                        // 旧兼容装配：行动池公开撤回口绑定 PLAYER 身份；模型不能选择 playerId。
                        ignored = actionPoolService.withdraw(
                                new Actor(current.playerId(), ActorRole.PLAYER),
                                actionId, key);
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("actionId", ignored.actionId());
                    data.put("status", ignored.status().name());
                    data.put("reason", reason);
                    return GmDecisionToolSupport.success(IGNORE, data);
                });
    }

    private static ActionStatus parseStatus(String value) {
        try {
            return ActionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new GmDecisionToolSupport.InvalidArgumentsException(
                    "status 必须是合法行动状态");
        }
    }

    private static Map<String, Object> summary(PlayerAction action) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("actionId", action.actionId());
        row.put("playerId", action.playerId());
        row.put("status", action.status().name());
        row.put("packageId", action.packageId());
        row.put("createdAt", action.createdAt());
        row.put("updatedAt", action.updatedAt());
        row.put("source", action.source());
        row.put("textLength", action.textLength());
        return row;
    }

    private static Map<String, Object> full(PlayerAction action) {
        Map<String, Object> row = summary(action);
        row.put("text", action.text());
        row.put("idempotencyKey", action.idempotencyKey());
        return row;
    }
}
