package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.agent.SecretaryWorkspaceTools;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只读读取指定玩家秘书工作区内的逻辑路径。
 *
 * <p>文件不存在返回 {@code NOT_FOUND}；越权路径返回稳定 {@code INVALID_ARGUMENTS}。
 * 结果截断到 16 KiB 并带固定标记，不返回物理路径或未授权异常文本。</p>
 */
public final class GmSecretaryReadTool implements AgentTool {

    public static final String NAME = "tf_gm_secretary_read";
    public static final int MAX_READ_RESULT_CHARS =
            SecretaryWorkspaceTools.MAX_READ_RESULT_CHARS;
    public static final String TRUNCATION_MARKER =
            SecretaryWorkspaceTools.TRUNCATION_MARKER;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "读取指定玩家秘书工作区中的一篇文本；路径必须是 public/player/gm/notes 下的相对路径。",
            "properties", Map.of(
                    "playerId", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "已装配玩家 ID"),
                    "path", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "工作区内相对 Docs 路径，如 gm/order.md")),
            "required", List.of("playerId", "path"),
            "additionalProperties", false);

    private final SecretaryWorkspaceStore store;
    private final SecretarySessionRegistry sessions;

    public GmSecretaryReadTool(SecretaryWorkspaceStore store,
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
        return "读取指定已装配玩家秘书工作区中的文本；只返回逻辑路径与截断后的内容，"
                + "不存在时返回 NOT_FOUND，不泄露物理路径。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(io.mosire.agentlib.permission.AccessToken.DEFAULT,
                true, false);
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
    public ToolResult execute(ToolContext context) {
        try {
            GmSecretaryToolSupport.requireRead(context);
            String playerId = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "playerId", 128);
            String path = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "path", 1024);
            if (sessions.find(playerId).isEmpty()) {
                return notFound();
            }

            String content;
            try {
                content = store.read(playerId, path).orElse(null);
            } catch (IllegalArgumentException invalid) {
                return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                        "工作区路径非法或不可读");
            }
            if (content == null) {
                return notFound();
            }

            boolean truncated = content.length() > MAX_READ_RESULT_CHARS;
            String rendered = truncated
                    ? content.substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                    : content;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("playerId", playerId);
            data.put("path", path);
            data.put("content", rendered);
            data.put("truncated", truncated);
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "INTERNAL_ERROR",
                    "秘书工作区读取失败");
        }
    }

    private static ToolResult notFound() {
        return GmSecretaryToolSupport.failure(NAME, "NOT_FOUND",
                "未找到玩家秘书工作区或指定文件");
    }
}
