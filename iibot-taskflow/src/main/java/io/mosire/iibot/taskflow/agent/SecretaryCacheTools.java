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
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheException;
import io.mosire.iibot.taskflow.cache.SecretaryCacheStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 玩家秘书读取自己历史 cache 的两个只读工具。
 *
 * <p>schema 中没有 playerId；工具通过绑定到当前 {@link SecretarySession} 的 playerId
 * 读取 cache，因此不能借参数读取其他玩家。读取结果只渲染 user/assistant 文本，不暴露
 * tool_call/tool_result 原文、system prompt 或物理路径。</p>
 */
public final class SecretaryCacheTools {

    public static final String LIST = "secretary_cache_list";
    public static final String READ = "secretary_cache_read";
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";
    public static final int MAX_READ_RESULT_CHARS = 16 * 1024;

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            SecretaryCacheStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            SecretaryCacheStore.RESOURCE_NAMESPACE, "conversation");

    private SecretaryCacheTools() {
    }

    /** 两个只读工具；顺序稳定。 */
    public static List<AgentTool> all(SecretarySession session) {
        Objects.requireNonNull(session, "session");
        return List.of(list(session), read(session));
    }

    public static AgentTool list(SecretarySession session) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
        return new CacheTool(
                LIST,
                "列出当前秘书可访问的历史会话 cache；只返回 cacheId、标题、来源和消息数，不返回正文。",
                schema,
                Operation.READ,
                context -> {
                    List<CacheSummary> caches = session.cacheSummaries();
                    String active = session.activeCacheId();
                    if (caches.isEmpty()) {
                        return ToolResult.ok("当前没有可读取的会话 cache。activeCacheId="
                                + (active == null ? "" : active));
                    }
                    StringBuilder out = new StringBuilder("activeCacheId=")
                            .append(active == null ? "" : active)
                            .append("；共 ").append(caches.size()).append(" 个 cache：\n");
                    for (CacheSummary cache : caches) {
                        out.append("- cacheId=").append(cache.cacheId())
                                .append(" | title=").append(cache.title())
                                .append(" | source=").append(cache.source())
                                .append(" | messages=").append(cache.messageCount())
                                .append(cache.active() ? " | ACTIVE" : "")
                                .append('\n');
                    }
                    return ToolResult.ok(out.toString().stripTrailing());
                });
    }

    public static AgentTool read(SecretarySession session) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "cacheId", Map.of(
                                "type", "string",
                                "description", "可选 cacheId；缺省读取当前 active cache"),
                        "limit", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", 50,
                                "description", "读取最近多少条可见对话，默认 20，最大 50")),
                "additionalProperties", false);
        return new CacheTool(
                READ,
                "读取当前秘书自己的一页历史对话（旧 cache 需显式给 cacheId）；只返回 user/assistant 可见文本。",
                schema,
                Operation.READ,
                context -> {
                    String cacheId = optionalText(context.arguments(), "cacheId");
                    int limit = optionalInt(context.arguments(), "limit", 20);
                    if (limit < 1 || limit > 50) {
                        return ToolResult.error("INVALID_ARGUMENTS", "limit 必须在 1–50 之间");
                    }
                    String transcript = session.readCacheTranscript(cacheId, limit);
                    if (transcript.length() > MAX_READ_RESULT_CHARS) {
                        return ToolResult.ok(transcript.substring(0, MAX_READ_RESULT_CHARS)
                                + TRUNCATION_MARKER);
                    }
                    return ToolResult.ok(transcript);
                });
    }

    private static String optionalText(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key)) {
            return null;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    key + " 必须是字符串");
        }
        if (text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> arguments, String key, int fallback) {
        if (arguments == null || !arguments.containsKey(key)) {
            return fallback;
        }
        Object value = arguments.get(key);
        if (!(value instanceof Number number)) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    key + " 必须是整数");
        }
        return number.intValue();
    }

    /** 统一工具壳：角色/资源断言 + 稳定错误映射。 */
    private static final class CacheTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final Function<ToolContext, ToolResult> body;

        private CacheTool(String name, String description, Map<String, Object> schema,
                          Operation operation, Function<ToolContext, ToolResult> body) {
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
                throw denied;
            } catch (SecretaryCacheException failure) {
                return ToolResult.error(failure.code(), failure.getMessage());
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(SecretaryCacheException.INVALID_ARGUMENTS,
                        invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error(SecretaryCacheException.CACHE_UNAVAILABLE,
                        "秘书历史 cache 暂不可用（内部错误已脱敏）");
            }
        }
    }
}
