package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GM 向指定玩家秘书工作区受控下发材料。
 *
 * <p>只允许写 {@code gm/} 或 {@code player/} 下的文件，拒绝覆盖已有路径。成功与失败结果
 * 都按 {@code idempotencyKey} 缓存首次 ToolResult，重复键不会再次写盘。工具结果不回显
 * 材料正文、物理路径或内部异常。</p>
 */
public final class GmSecretaryDispatchTool implements AgentTool {

    public static final String NAME = "tf_gm_secretary_dispatch";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "向指定玩家秘书工作区下发一篇材料；只能写 gm/ 或 player/，"
                    + "不覆盖已有文件，同一 idempotencyKey 只执行一次。",
            "properties", Map.of(
                    "playerId", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "已装配玩家 ID"),
                    "path", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "gm/ 或 player/ 下的目标相对路径"),
                    "content", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "maxLength", SecretaryWorkspaceStore.MAX_CONTENT_CHARS,
                            "description", "要下发的材料正文"),
                    "idempotencyKey", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "maxLength", GmSecretaryToolSupport.MAX_IDEMPOTENCY_KEY_CHARS,
                            "description", "幂等键；同一键只写一次并返回首次结果"),
                    "reason", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "maxLength", GmSecretaryToolSupport.MAX_REASON_CHARS,
                            "description", "下发原因摘要")),
            "required", List.of("playerId", "path", "content", "idempotencyKey", "reason"),
            "additionalProperties", false);

    private final SecretaryWorkspaceStore store;
    private final SecretarySessionRegistry sessions;
    private final Object cacheLock = new Object();
    private final Map<String, ToolResult> results = new LinkedHashMap<>();

    public GmSecretaryDispatchTool(SecretaryWorkspaceStore store,
                                   SecretarySessionRegistry sessions) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "GM 向指定玩家秘书工作区的 gm/ 或 player/ 受控下发材料；拒绝覆盖已有文件，"
                + "同一 idempotencyKey 缓存首次结果且不重复写盘。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public java.util.Set<String> roles() {
        return GmSecretaryToolSupport.ROLES;
    }

    @Override
    public io.mosire.agentlib.permission.ResourceManifest resources() {
        return GmSecretaryToolSupport.RESOURCES;
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        copyString(context.arguments(), safe, "playerId");
        copyString(context.arguments(), safe, "path");
        copyString(context.arguments(), safe, "idempotencyKey");
        return Map.copyOf(safe);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmSecretaryToolSupport.requireWrite(context);
            Arguments arguments = Arguments.from(context.arguments());
            synchronized (cacheLock) {
                ToolResult cached = results.get(arguments.idempotencyKey());
                if (cached != null) {
                    return cached;
                }
                ToolResult result = dispatch(arguments);
                results.put(arguments.idempotencyKey(), result);
                return result;
            }
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "INTERNAL_ERROR",
                    "秘书工作区下发失败");
        }
    }

    private ToolResult dispatch(Arguments arguments) {
        if (sessions.find(arguments.playerId()).isEmpty()) {
            return GmSecretaryToolSupport.failure(NAME, "NOT_FOUND",
                    "未找到指定玩家的秘书会话");
        }

        try {
            if (store.read(arguments.playerId(), arguments.path()).isPresent()) {
                return conflict();
            }
            store.writeGmMaterial(arguments.playerId(), arguments.path(),
                    arguments.content());
        } catch (IllegalArgumentException invalid) {
            String detail = invalid.getMessage() == null ? "" : invalid.getMessage();
            if (detail.contains("已存在")) {
                return conflict();
            }
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    "下发路径非法或内容不可写");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerId", arguments.playerId());
        data.put("path", arguments.path());
        data.put("contentChars", arguments.content().length());
        return GmSecretaryToolSupport.success(NAME, data);
    }

    private static ToolResult conflict() {
        return GmSecretaryToolSupport.failure(NAME, "CONFLICT",
                "目标材料路径已存在，拒绝覆盖");
    }

    private static void copyString(Map<String, Object> arguments,
                                   Map<String, Object> target, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value instanceof String text) {
            target.put(name, text);
        }
    }

    private record Arguments(String playerId, String path, String content,
                             String idempotencyKey, String reason) {
        static Arguments from(Map<String, Object> raw) {
            Map<String, Object> arguments = raw == null ? Map.of() : raw;
            String playerId = GmSecretaryToolSupport.requiredText(
                    arguments, "playerId", 128);
            String path = GmSecretaryToolSupport.requiredText(arguments, "path", 1024);
            String content = GmSecretaryToolSupport.requiredText(
                    arguments, "content", SecretaryWorkspaceStore.MAX_CONTENT_CHARS);
            String idempotencyKey = GmSecretaryToolSupport.requiredText(
                    arguments, "idempotencyKey",
                    GmSecretaryToolSupport.MAX_IDEMPOTENCY_KEY_CHARS);
            String reason = GmSecretaryToolSupport.requiredText(
                    arguments, "reason", GmSecretaryToolSupport.MAX_REASON_CHARS);
            return new Arguments(playerId, path, content, idempotencyKey, reason);
        }
    }
}
