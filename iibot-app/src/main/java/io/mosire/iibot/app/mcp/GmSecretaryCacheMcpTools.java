package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GM 侧读取、创建和切换秘书对话 cache 的只读/受控工具集。
 *
 * <p>这些工具只操作 {@link SecretarySession} 的 cache，不发送 QQ、不写世界、不改变玩家
 * 身份。读取结果只包含玩家可见的 user/assistant 文本；tool_call/tool_result 不返回。</p>
 */
public final class GmSecretaryCacheMcpTools {

    public static final String LIST = "tf_gm_secretary_cache_list";
    public static final String CREATE = "tf_gm_secretary_cache_create";
    public static final String READ = "tf_gm_secretary_cache_read";
    public static final String SWITCH = "tf_gm_secretary_cache_switch";

    public static final List<String> NAMES = List.of(LIST, CREATE, READ, SWITCH);
    public static final int MAX_READ_RESULT_CHARS = 16 * 1024;
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    private GmSecretaryCacheMcpTools() {
    }

    public static List<AgentTool> all(SecretarySessionRegistry sessions) {
        Objects.requireNonNull(sessions, "sessions");
        return List.of(
                new ListTool(sessions),
                new CreateTool(sessions),
                new ReadTool(sessions),
                new SwitchTool(sessions));
    }

    private abstract static class CacheTool implements AgentTool {
        protected final SecretarySessionRegistry sessions;

        private CacheTool(SecretarySessionRegistry sessions) {
            this.sessions = sessions;
        }

        protected SecretarySession requireSession(String playerId) {
            return sessions.find(playerId).orElse(null);
        }

        @Override
        public Set<String> roles() {
            return GmSecretaryToolSupport.ROLES;
        }

        @Override
        public io.mosire.agentlib.permission.ResourceManifest resources() {
            return GmSecretaryToolSupport.CACHE_RESOURCES;
        }

        protected ToolResult failure(String code, String message) {
            return GmSecretaryToolSupport.failure(name(), code, message);
        }

        protected ToolResult cacheFailure(SecretaryCacheException failure) {
            return failure(failure.code(), failure.getMessage());
        }
    }

    private static final class ListTool extends CacheTool {
        private static final Map<String, Object> SCHEMA = Map.of(
                "type", "object",
                "description", "列出指定玩家秘书的所有会话 cache；不返回正文。",
                "properties", Map.of(
                        "playerId", Map.of("type", "string", "minLength", 1,
                                "description", "已装配玩家 ID")),
                "required", List.of("playerId"),
                "additionalProperties", false);

        private ListTool(SecretarySessionRegistry sessions) {
            super(sessions);
        }

        @Override
        public String name() {
            return LIST;
        }

        @Override
        public String description() {
            return "只读列出指定玩家秘书的 cache 元数据与当前 active cache；不发送 QQ。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return SCHEMA;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                GmSecretaryToolSupport.requireCacheRead(context);
                String playerId = GmSecretaryToolSupport.requiredText(
                        context.arguments(), "playerId", 128);
                SecretarySession session = requireSession(playerId);
                if (session == null) {
                    return failure("NOT_FOUND", "未找到指定玩家的秘书会话");
                }
                List<Map<String, Object>> caches = new ArrayList<>();
                for (CacheSummary cache : session.cacheSummaries()) {
                    caches.add(cacheMap(cache));
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("playerId", playerId);
                data.put("activeCacheId", session.activeCacheId());
                data.put("count", caches.size());
                data.put("caches", caches);
                return GmSecretaryToolSupport.success(name(), data);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (SecretaryCacheException failure) {
                return cacheFailure(failure);
            } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
                return failure("INVALID_ARGUMENTS", invalid.getMessage());
            } catch (RuntimeException failure) {
                return failure("INTERNAL_ERROR", "秘书 cache 读取失败");
            }
        }
    }

    private static final class CreateTool extends CacheTool {
        private static final Map<String, Object> SCHEMA = Map.of(
                "type", "object",
                "description", "为指定玩家秘书新建一个空 cache；不自动切换 active。",
                "properties", Map.of(
                        "playerId", Map.of("type", "string", "minLength", 1,
                                "description", "已装配玩家 ID"),
                        "title", Map.of("type", "string", "maxLength", 200,
                                "description", "可选展示标题")),
                "required", List.of("playerId"),
                "additionalProperties", false);

        private CreateTool(SecretarySessionRegistry sessions) {
            super(sessions);
        }

        @Override
        public String name() {
            return CREATE;
        }

        @Override
        public String description() {
            return "为指定玩家新建一个空 cache；不发送 QQ、不切换 active。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return SCHEMA;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            Map<String, Object> safe = new LinkedHashMap<>();
            copyArg(context, safe, "playerId");
            copyArg(context, safe, "title");
            return Map.copyOf(safe);
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                GmSecretaryToolSupport.requireCacheWrite(context);
                String playerId = GmSecretaryToolSupport.requiredText(
                        context.arguments(), "playerId", 128);
                SecretarySession session = requireSession(playerId);
                if (session == null) {
                    return failure("NOT_FOUND", "未找到指定玩家的秘书会话");
                }
                String title = optionalText(context.arguments(), "title", 200);
                CacheSummary created = session.createCache(title);
                return GmSecretaryToolSupport.success(name(), cacheMap(created));
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (SecretaryCacheException failure) {
                return cacheFailure(failure);
            } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
                return failure("INVALID_ARGUMENTS", invalid.getMessage());
            } catch (RuntimeException failure) {
                return failure("INTERNAL_ERROR", "创建秘书 cache 失败");
            }
        }
    }

    private static final class ReadTool extends CacheTool {
        private static final Map<String, Object> SCHEMA = Map.of(
                "type", "object",
                "description", "读取指定玩家秘书 cache 的可见对话文本；不返回工具调用原文。",
                "properties", Map.of(
                        "playerId", Map.of("type", "string", "minLength", 1,
                                "description", "已装配玩家 ID"),
                        "cacheId", Map.of("type", "string", "minLength", 1,
                                "description", "可选 cacheId；缺省读取 active cache"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50,
                                "description", "最近对话条数，默认 20")),
                "required", List.of("playerId"),
                "additionalProperties", false);

        private ReadTool(SecretarySessionRegistry sessions) {
            super(sessions);
        }

        @Override
        public String name() {
            return READ;
        }

        @Override
        public String description() {
            return "只读读取指定玩家秘书 cache 的玩家可见对话；不发送 QQ、不返回物理路径。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return SCHEMA;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            Map<String, Object> safe = new LinkedHashMap<>();
            copyArg(context, safe, "playerId");
            copyArg(context, safe, "cacheId");
            copyArg(context, safe, "limit");
            return Map.copyOf(safe);
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                GmSecretaryToolSupport.requireCacheRead(context);
                String playerId = GmSecretaryToolSupport.requiredText(
                        context.arguments(), "playerId", 128);
                String cacheId = optionalText(context.arguments(), "cacheId", 80);
                int limit = optionalInt(context.arguments(), "limit", 20);
                if (limit < 1 || limit > 50) {
                    return failure("INVALID_ARGUMENTS", "limit 必须在 1–50 之间");
                }
                SecretarySession session = requireSession(playerId);
                if (session == null) {
                    return failure("NOT_FOUND", "未找到指定玩家的秘书会话");
                }
                String transcript = session.readCacheTranscript(cacheId, limit);
                boolean truncated = transcript.length() > MAX_READ_RESULT_CHARS;
                String rendered = truncated
                        ? transcript.substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                        : transcript;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("playerId", playerId);
                data.put("activeCacheId", session.activeCacheId());
                data.put("cacheId", cacheId == null ? session.activeCacheId() : cacheId);
                data.put("transcript", rendered);
                data.put("truncated", truncated);
                return GmSecretaryToolSupport.success(name(), data);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (SecretaryCacheException failure) {
                return cacheFailure(failure);
            } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
                return failure("INVALID_ARGUMENTS", invalid.getMessage());
            } catch (RuntimeException failure) {
                return failure("INTERNAL_ERROR", "读取秘书 cache 失败");
            }
        }
    }

    private static final class SwitchTool extends CacheTool {
        private static final Map<String, Object> SCHEMA = Map.of(
                "type", "object",
                "description", "切换指定玩家秘书的 active cache；不改变身份、不发送 QQ。",
                "properties", Map.of(
                        "playerId", Map.of("type", "string", "minLength", 1,
                                "description", "已装配玩家 ID"),
                        "cacheId", Map.of("type", "string", "minLength", 1,
                                "description", "目标 cacheId，必须属于同一玩家"),
                        "reason", Map.of("type", "string", "minLength", 1,
                                "maxLength", GmSecretaryToolSupport.MAX_REASON_CHARS,
                                "description", "切换原因，仅用于审计")),
                "required", List.of("playerId", "cacheId", "reason"),
                "additionalProperties", false);

        private SwitchTool(SecretarySessionRegistry sessions) {
            super(sessions);
        }

        @Override
        public String name() {
            return SWITCH;
        }

        @Override
        public String description() {
            return "切换指定玩家秘书的 active cache；目标必须属于同一玩家；不发送 QQ、不写世界。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return SCHEMA;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            Map<String, Object> safe = new LinkedHashMap<>();
            copyArg(context, safe, "playerId");
            copyArg(context, safe, "cacheId");
            copyArg(context, safe, "reason");
            return Map.copyOf(safe);
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                GmSecretaryToolSupport.requireCacheWrite(context);
                String playerId = GmSecretaryToolSupport.requiredText(
                        context.arguments(), "playerId", 128);
                String cacheId = GmSecretaryToolSupport.requiredText(
                        context.arguments(), "cacheId", 80);
                GmSecretaryToolSupport.requiredText(context.arguments(), "reason",
                        GmSecretaryToolSupport.MAX_REASON_CHARS);
                SecretarySession session = requireSession(playerId);
                if (session == null) {
                    return failure("NOT_FOUND", "未找到指定玩家的秘书会话");
                }
                session.switchCache(cacheId);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("playerId", playerId);
                data.put("activeCacheId", session.activeCacheId());
                return GmSecretaryToolSupport.success(name(), data);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (SecretaryCacheException failure) {
                return cacheFailure(failure);
            } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
                return failure("INVALID_ARGUMENTS", invalid.getMessage());
            } catch (RuntimeException failure) {
                return failure("INTERNAL_ERROR", "切换秘书 cache 失败");
            }
        }
    }

    private static Map<String, Object> cacheMap(CacheSummary cache) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("cacheId", cache.cacheId());
        item.put("title", cache.title());
        item.put("source", cache.source());
        item.put("createdAt", cache.createdAt());
        item.put("lastUsedAt", cache.lastUsedAt());
        item.put("messageCount", cache.messageCount());
        item.put("active", cache.active());
        return item;
    }

    private static void copyArg(ToolContext context, Map<String, Object> target, String name) {
        Object value = context.arguments() == null ? null : context.arguments().get(name);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(name, value);
        }
    }

    private static String optionalText(Map<String, Object> arguments, String name, int maxChars) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 必须是字符串");
        }
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > maxChars) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 超过长度上限");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 必须是整数");
        }
        return number.intValue();
    }
}
