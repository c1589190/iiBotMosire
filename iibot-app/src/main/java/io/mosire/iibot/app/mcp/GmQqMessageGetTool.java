package io.mosire.iibot.app.mcp;

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
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.QqPlatformCapability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 按消息 ID 读取一条 Bot 可访问的 QQ 消息，不返回原始协议 JSON。 */
public final class GmQqMessageGetTool implements AgentTool {

    public static final String NAME = "tf_qq_message_get";
    public static final String RESOURCE_NAMESPACE = "qq-history";
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "read");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按消息 ID 读取一条 Bot 可访问的 QQ 文本消息。",
            "properties", Map.of(
                    "messageId", Map.of("type", "string", "minLength", 1,
                            "description", "QQ 消息 ID")),
            "required", List.of("messageId"),
            "additionalProperties", false);

    private final QqPlatformCapability platform;

    public GmQqMessageGetTool(QqPlatformCapability platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读按消息 ID 取一条 QQ 消息文本；不发送、不写 cache。";
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
            context.resources().require(Operation.READ, RESOURCE);
            String messageId = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "messageId", 128);
            ChatMessageDetail message = platform.message(messageId)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", message.messageId());
            data.put("chatType", message.sessionType().name().toLowerCase());
            data.put("targetId", message.targetId());
            data.put("senderId", message.senderId());
            data.put("senderName", message.senderName());
            data.put("text", message.text());
            data.put("sentAt", message.sentAt() == null ? null : message.sentAt().toString());
            data.put("nonTextSegments", message.nonTextSegments());
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (TimeoutException timeout) {
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_TIMEOUT", "消息读取超时");
        } catch (ExecutionException failure) {
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_UNAVAILABLE",
                    "消息不存在或当前不可访问");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_INTERRUPTED", "消息读取被中断");
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_UNAVAILABLE",
                    "消息读取失败（内部原因已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        return args != null && args.get("messageId") instanceof String messageId
                ? Map.of("messageId", messageId) : Map.of();
    }
}
