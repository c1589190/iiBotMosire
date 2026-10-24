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
import io.mosire.iibot.app.control.QqPlatformSendService;
import io.mosire.iibot.taskflow.Actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 平台级任意私聊发送；只在 allowArbitrarySend 开启时注册。 */
public final class GmQqSendPrivateTool implements AgentTool {

    public static final String NAME = "tf_qq_send_private";
    public static final String RESOURCE_NAMESPACE = "qq-platform-send";
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "write");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "平台级任意私聊文本发送；高风险、默认关闭，仅受控操作员启用。",
            "properties", Map.of(
                    "qqId", Map.of("type", "string", "minLength", 1),
                    "temporaryGroupId", Map.of("type", "string",
                            "description", "可选来源群，用于群临时会话"),
                    "text", Map.of("type", "string", "minLength", 1),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1)),
            "required", List.of("qqId", "text", "idempotencyKey"),
            "additionalProperties", false);

    private final QqPlatformSendService service;
    private final Actor supervisor;

    public GmQqSendPrivateTool(QqPlatformSendService service, Actor supervisor) {
        this.service = Objects.requireNonNull(service, "service");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "平台级任意私聊发送；默认关闭，有 delivery ledger 和正文守卫。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override public Set<String> roles() { return ROLES; }

    @Override public ResourceManifest resources() { return RESOURCES; }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            context.resources().require(Operation.WRITE, RESOURCE);
            QqPlatformSendService.Result result = service.sendPrivate(
                    supervisor,
                    optionalText(context.arguments(), "qqId"),
                    optionalText(context.arguments(), "temporaryGroupId"),
                    optionalText(context.arguments(), "text"),
                    optionalText(context.arguments(), "idempotencyKey"));
            return result(result);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (QqPlatformSendService.PlatformSendException failure) {
            return GmSecretaryToolSupport.failure(NAME, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "PLATFORM_SEND_UNAVAILABLE",
                    "平台发送暂不可用（内部原因已脱敏）");
        }
    }

    private ToolResult result(QqPlatformSendService.Result result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryState", result.deliveryState());
        data.put("messageIds", result.messageIds());
        data.put("alreadyAttempted", result.alreadyAttempted());
        return GmSecretaryToolSupport.success(NAME, data);
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String name : List.of("qqId", "temporaryGroupId", "idempotencyKey")) {
                if (args.containsKey(name)) safe.put(name, args.get(name));
            }
        }
        return Map.copyOf(safe);
    }

    private static String optionalText(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new QqPlatformSendService.PlatformSendException(
                    "INVALID_ARGUMENTS", "参数 " + name + " 必须是非空白字符串");
        }
        return text.trim();
    }
}
