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
import io.mosire.iibot.app.control.PlayerOnboardingService;
import io.mosire.iibot.taskflow.Actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 公屏玩家自主入团：来源消息校验 → 注册玩家 → 绑定秘书 → 发送报名卡。 */
public final class GmPlayerOnboardFromGroupTool implements AgentTool {

    public static final String NAME = "tf_player_onboard_from_group";
    public static final String RESOURCE_NAMESPACE = "qq-onboarding";
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "write");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "根据群消息证据自主注册玩家、绑定秘书并发送报名卡；"
                    + "只允许策略允许的群，不向未报名用户主动私聊。",
            "properties", Map.of(
                    "sourceGroupId", Map.of("type", "string", "minLength", 1),
                    "sourceMessageId", Map.of("type", "string", "minLength", 1),
                    "qqId", Map.of("type", "string", "minLength", 1),
                    "displayName", Map.of("type", "string"),
                    "registrationForm", Map.of("type", "string"),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1)),
            "required", List.of("sourceGroupId", "sourceMessageId", "qqId", "idempotencyKey"),
            "additionalProperties", false);

    private final PlayerOnboardingService service;
    private final Actor supervisor;

    public GmPlayerOnboardFromGroupTool(PlayerOnboardingService service, Actor supervisor) {
        this.service = Objects.requireNonNull(service, "service");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "自主入团：校验群消息来源后注册玩家、绑定秘书并发送报名卡；"
                + "受群 allowlist、人数和 delivery ledger 限制。";
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
            context.resources().require(Operation.WRITE, RESOURCE);
            PlayerOnboardingService.Result result = service.onboard(
                    supervisor,
                    optionalText(context.arguments(), "sourceGroupId", 64),
                    optionalText(context.arguments(), "sourceMessageId", 128),
                    optionalText(context.arguments(), "qqId", 64),
                    optionalText(context.arguments(), "displayName", 200),
                    optionalText(context.arguments(), "registrationForm", 4_000),
                    optionalText(context.arguments(), "idempotencyKey", 200));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("playerId", result.playerId());
            data.put("qqId", result.qqId());
            data.put("secretaryBound", result.secretaryBound());
            data.put("alreadyRegistered", result.alreadyRegistered());
            data.put("alreadyClaimed", result.alreadyClaimed());
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("deliveryState", result.deliveryState());
            card.put("messageIds", result.messageIds());
            data.put("registrationCard", card);
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (PlayerOnboardingService.OnboardingException failure) {
            return GmSecretaryToolSupport.failure(NAME, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "ONBOARDING_UNAVAILABLE",
                    "自主入团暂不可用（内部原因已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String name : List.of("sourceGroupId", "sourceMessageId", "qqId",
                    "idempotencyKey")) {
                if (args.containsKey(name)) {
                    safe.put(name, args.get(name));
                }
            }
        }
        return Map.copyOf(safe);
    }

    private static String optionalText(Map<String, Object> args, String name, int max) {
        Object value = args == null ? null : args.get(name);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        if (text.length() > max) {
            throw new PlayerOnboardingService.OnboardingException(
                    "INVALID_ARGUMENTS", "参数 " + name + " 超过长度限制");
        }
        return text.trim();
    }
}
