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
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家秘书专用的三个工作区工具。
 *
 * <p>工具只从宿主注入的 {@link ToolContext#config()} 读取 playerId；schema 中没有任何
 * playerId 参数。全部工具声明 {@code secretary} 角色与 {@code secretary-workspace}
 * 资源面，实际执行前还要经 {@link io.mosire.agentlib.tool.ToolCallAuthorizer} 的工具名、
 * 角色、资源三重判定。</p>
 *
 * <p><b>面向模型的输出只有逻辑路径和内容</b>：不包含 {@code rootFor(...)} 的物理路径，
 * 也不包含其他玩家 ID。文本只在读取结果中出现；列表与 note 回执不带正文。</p>
 */
public final class SecretaryWorkspaceTools {

    public static final String LIST = "secretary_workspace_list";
    public static final String READ = "secretary_workspace_read";
    public static final String NOTE = "secretary_workspace_note";

    /** 单次读取回给模型的最大字符数；超过则截断并带固定标记。 */
    public static final int MAX_READ_RESULT_CHARS = 16 * 1024;
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            SecretaryWorkspaceStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            SecretaryWorkspaceStore.RESOURCE_NAMESPACE, "workspace");

    private SecretaryWorkspaceTools() {
    }

    /** 三个工具；顺序稳定，便于会话装配与测试对账。 */
    public static List<AgentTool> all(SecretaryWorkspaceStore store) {
        Objects.requireNonNull(store, "store");
        return List.of(list(store), read(store), note(store));
    }

    public static AgentTool list(SecretaryWorkspaceStore store) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "可选相对路径：public、player、gm、notes 或其子目录；缺省列出四个根")),
                "additionalProperties", false);
        return new WorkspaceTool(
                LIST,
                "列出当前玩家秘书工作区的直接子项。path 缺省为工作区根；只返回逻辑相对路径。",
                schema,
                Operation.READ,
                context -> {
                    String path = optionalText(context.arguments(), "path", "");
                    List<String> entries = store.list(requirePlayerId(context), path);
                    if (entries.isEmpty()) {
                        return ToolResult.ok("（目录为空）");
                    }
                    StringBuilder result = new StringBuilder("共 ").append(entries.size()).append(" 项：\n");
                    for (String entry : entries) {
                        result.append("- ").append(entry).append('\n');
                    }
                    return ToolResult.ok(result.toString().stripTrailing());
                });
    }

    public static AgentTool read(SecretaryWorkspaceStore store) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "必填相对 Docs 路径，如 public/notice.md、player/brief.md、gm/order.md")),
                "required", List.of("path"),
                "additionalProperties", false);
        return new WorkspaceTool(
                READ,
                "读取当前玩家秘书工作区中的一篇文本；不存在时返回明确的未找到，不读取其他玩家工作区。",
                schema,
                Operation.READ,
                context -> {
                    String path = requiredText(context.arguments(), "path");
                    String content = store.read(requirePlayerId(context), path)
                            .orElse(null);
                    if (content == null) {
                        return ToolResult.error("WORKSPACE_NOT_FOUND", "工作区中不存在该文件");
                    }
                    String rendered = content.length() > MAX_READ_RESULT_CHARS
                            ? content.substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                            : content;
                    return ToolResult.ok("路径=" + path + "\n内容：\n" + rendered);
                });
    }

    public static AgentTool note(SecretaryWorkspaceStore store) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of(
                                "type", "string",
                                "description", "要写入当前玩家 notes/ 的笔记正文")),
                "required", List.of("text"),
                "additionalProperties", false);
        return new WorkspaceTool(
                NOTE,
                "把一篇笔记追加保存到当前玩家秘书工作区的 notes/；不能写入 public/、player/ 或 gm/。",
                schema,
                Operation.WRITE,
                context -> {
                    String text = requiredText(context.arguments(), "text");
                    store.appendNote(requirePlayerId(context), text);
                    return ToolResult.ok("笔记已保存到 notes/");
                }) {
            @Override
            public Map<String, Object> ledgerArgs(ToolContext context) {
                // 笔记正文不进入工具调用审计参数；只留工具名级事实。
                return Map.of();
            }
        };
    }

    private static String requirePlayerId(ToolContext context) {
        Object value = context.config().get(SecretaryToolContext.PLAYER_ID);
        if (!(value instanceof String playerId) || playerId.isBlank()) {
            throw new IllegalArgumentException("宿主未绑定合法 playerId");
        }
        Object actorValue = context.config().get(SecretaryToolContext.ACTOR);
        if (actorValue instanceof Actor actor) {
            if (actor.role() != ActorRole.PLAYER || !actor.actorId().equals(playerId)) {
                throw new IllegalArgumentException("宿主绑定的 actor 与 playerId 不一致");
            }
        }
        return playerId;
    }

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> arguments, String key, String fallback) {
        if (!arguments.containsKey(key)) {
            return fallback;
        }
        Object value = arguments.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须是字符串");
        }
        return text;
    }

    /** 统一工具壳：metadata + 资源断言 + 稳定错误映射。 */
    private static class WorkspaceTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final java.util.function.Function<ToolContext, ToolResult> body;

        private WorkspaceTool(String name, String description, Map<String, Object> schema,
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
                context.resources().require(operation, RESOURCE_ID);
                return body.apply(context);
            } catch (ResourceDeniedException denied) {
                // 唯一入口必须看见资源拒绝；吞掉会把 deny 静默降级成普通工具失败。
                throw denied;
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error("WORKSPACE_UNAVAILABLE", "工作区暂不可用（内部错误已脱敏）");
            }
        }
    }
}
