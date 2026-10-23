package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外部 supervisor 与指定玩家秘书对话的只读 MCP 工具。
 *
 * <p>工具只调用已装配会话的 {@link SecretarySession#gmChat(String)} 并返回最终文本；
 * 不发送 QQ、不返回 prompt/工具审计/异常/密钥。LLM 失败由会话自身的稳定降级文本处理。</p>
 */
public final class GmSecretaryChatTool implements AgentTool {

    public static final String NAME = "tf_gm_secretary_chat";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "向指定玩家的秘书发送 GM 消息并返回秘书回复；不发送 QQ、不写世界。",
            "properties", Map.of(
                    "playerId", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "已装配玩家 ID"),
                    "message", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "maxLength", GmSecretaryToolSupport.MAX_CHAT_MESSAGE_CHARS,
                            "description", "发给该玩家秘书的 GM 消息")),
            "required", List.of("playerId", "message"),
            "additionalProperties", false);

    private final SecretarySessionRegistry sessions;

    public GmSecretaryChatTool(SecretarySessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "与指定已装配玩家的秘书对话；回复只返回 MCP 调用者，不发送 QQ、"
                + "不返回系统 prompt、异常或密钥。";
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
        Object playerId = context.arguments() == null
                ? null : context.arguments().get("playerId");
        if (playerId instanceof String text) {
            safe.put("playerId", text);
        }
        return Map.copyOf(safe);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmSecretaryToolSupport.requireRead(context);
            String playerId = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "playerId", 128);
            String message = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "message",
                    GmSecretaryToolSupport.MAX_CHAT_MESSAGE_CHARS);
            SecretarySession session = sessions.find(playerId).orElse(null);
            if (session == null) {
                return GmSecretaryToolSupport.failure(NAME, "NOT_FOUND",
                        "未找到指定玩家的秘书会话");
            }
            return ToolResult.ok(session.gmChat(message));
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "INTERNAL_ERROR",
                    "玩家秘书暂时不可用");
        }
    }
}
