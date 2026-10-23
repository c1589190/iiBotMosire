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
import io.mosire.iibot.taskflow.skill.SharedSkillStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家秘书的两个全局共享 Skill 只读工具。
 *
 * <p>工具与 {@link SecretaryWorkspaceTools} 分离：共享内容不按玩家复制，也不把公共文档写进
 * 玩家工作区。schema 中没有 {@code playerId}（甚至没有玩家身份参数），实际调用仍由
 * {@link io.mosire.agentlib.tool.ToolCallAuthorizer} 做工具名、角色与 {@code shared-skills}
 * 资源面的三重判定。工具结果只有逻辑路径与正文，不含物理路径。</p>
 */
public final class SecretarySharedSkillTools {

    public static final String LIST = "secretary_shared_skill_list";
    public static final String READ = "secretary_shared_skill_read";
    public static final String LIST_TOOL = LIST;
    public static final String READ_TOOL = READ;

    public static final String SHARED_SKILL_NOT_FOUND = "SHARED_SKILL_NOT_FOUND";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String SHARED_SKILLS_UNAVAILABLE = "SHARED_SKILLS_UNAVAILABLE";

    /** 单次读取回给模型的最大字符数；超过则截断并带固定标记。 */
    public static final int MAX_READ_RESULT_CHARS = 16 * 1024;
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            SharedSkillStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            SharedSkillStore.RESOURCE_NAMESPACE, SharedSkillStore.RESOURCE_NAMESPACE);

    private SecretarySharedSkillTools() {
    }

    /** 两个只读工具；顺序稳定，便于会话装配与测试对账。 */
    public static List<AgentTool> all(SharedSkillStore store) {
        Objects.requireNonNull(store, "store");
        return List.of(list(store), read(store));
    }

    public static AgentTool list(SharedSkillStore store) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "可选相对路径：public、skills 或其子目录；缺省列出两个根")),
                "additionalProperties", false);
        return new ReadTool(
                LIST,
                "列出全局共享 Skill 区的直接子项（public/、skills/ 或子目录）；只返回逻辑相对路径。",
                schema,
                context -> {
                    String path = optionalText(context.arguments(), "path", "");
                    List<String> entries = store.list(path);
                    if (entries.isEmpty()) {
                        return "（目录为空）";
                    }
                    StringBuilder result = new StringBuilder("共 ").append(entries.size()).append(" 项：");
                    for (String entry : entries) {
                        result.append("\n- ").append(entry);
                    }
                    return result.toString();
                });
    }

    public static AgentTool read(SharedSkillStore store) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "必填相对 Docs 路径，如 public/notice.md、skills/response-guide.md")),
                "required", List.of("path"),
                "additionalProperties", false);
        return new ReadTool(
                READ,
                "读取全局共享 Skill 区中的一篇文本；不存在时返回明确的未找到，不写共享区。",
                schema,
                context -> {
                    String path = requiredText(context.arguments(), "path");
                    String content = store.read(path).orElse(null);
                    if (content == null) {
                        throw new ToolFailure(SHARED_SKILL_NOT_FOUND, "共享 Skill 区中不存在该文件");
                    }
                    boolean truncated = content.length() > MAX_READ_RESULT_CHARS;
                    String rendered = truncated
                            ? content.substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                            : content;
                    return "path=" + path + "\ntruncated=" + truncated + "\n内容：\n" + rendered;
                });
    }

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolFailure(INVALID_ARGUMENTS, key + " 必须是非空字符串");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> arguments, String key,
                                       String fallback) {
        if (arguments == null || !arguments.containsKey(key)) {
            return fallback;
        }
        Object value = arguments.get(key);
        if (!(value instanceof String text)) {
            throw new ToolFailure(INVALID_ARGUMENTS, key + " 必须是字符串");
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

    /** 只读工具壳：metadata + 资源断言 + 稳定错误映射。 */
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
                return ToolResult.error(SHARED_SKILLS_UNAVAILABLE,
                        "共享 Skill 区暂不可用（内部错误已脱敏）");
            }
        }
    }
}
