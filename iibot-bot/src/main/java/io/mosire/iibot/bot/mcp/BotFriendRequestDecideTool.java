package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.friend.FriendRequestDecision;
import io.mosire.iibot.bot.friend.FriendRequestService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 审批入站好友申请；不提供主动加好友。 */
public final class BotFriendRequestDecideTool implements AgentTool {

    public static final String NAME = "bot_friend_request_decide";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "requestId", Map.of("type", "string", "minLength", 1),
                    "approve", Map.of("type", "boolean"),
                    "remark", Map.of("type", "string")),
            "required", List.of("requestId", "approve"),
            "additionalProperties", false);

    private final FriendRequestService service;

    public BotFriendRequestDecideTool(FriendRequestService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "同意/拒绝入站好友申请；requestId 来自 list，raw flag 不出现在参数中。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String requestId = BotToolSupport.requiredText(
                    context.arguments(), "requestId", 512);
            Object approveValue = context.arguments().get("approve");
            if (!(approveValue instanceof Boolean approve)) {
                throw new BotToolSupport.InvalidArgumentsException("approve 必须是布尔值");
            }
            String remark = BotToolSupport.optionalText(
                    context.arguments(), "remark", 512);
            FriendRequestDecision decision = service.decide(requestId, approve, remark);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestId", decision.requestId());
            data.put("status", decision.status());
            data.put("idempotent", decision.idempotent());
            data.put("deliveryState", decision.deliveryState());
            data.put("errorCode", decision.errorCode());
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return BotToolSupport.failure("FRIEND_REQUEST_NOT_FOUND", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("FRIEND_REQUEST_UNAVAILABLE",
                    "好友申请审批暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("requestId", "approve", "remark")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
