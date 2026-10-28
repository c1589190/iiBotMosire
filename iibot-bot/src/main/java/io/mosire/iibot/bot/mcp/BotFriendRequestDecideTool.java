package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.friend.FriendRequestDecision;
import io.mosire.iibot.bot.friend.FriendRequestService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按账号审批入站好友申请；不提供主动加好友。 */
public final class BotFriendRequestDecideTool implements AgentTool {

    public static final String NAME = "qq_friend_request_decide";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "requestId", Map.of("type", "string", "minLength", 1),
                    "approve", Map.of("type", "boolean"),
                    "remark", Map.of("type", "string")),
            "required", List.of("requestId", "approve"),
            "additionalProperties", false);

    private final Map<String, FriendRequestService> services;

    /**
     * @param services accountId → 该账号的好友申请服务；value 为 null 表示该账号不支持好友
     *                 申请能力
     */
    public BotFriendRequestDecideTool(Map<String, FriendRequestService> services) {
        Objects.requireNonNull(services, "services");
        if (services.isEmpty()) {
            throw new IllegalArgumentException("services 不能为空");
        }
        this.services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /** 单账号便捷构造。 */
    public BotFriendRequestDecideTool(String accountId, FriendRequestService service) {
        this(single(accountId, service));
    }

    private static Map<String, FriendRequestService> single(String accountId,
                                                             FriendRequestService service) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(service, "service");
        return Map.of(accountId.trim(), service);
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "同意/拒绝某个 Bot 账号的入站好友申请；requestId 来自 list，raw flag 不出现在"
                + "参数中。UNKNOWN 结果不会自动重试。多账号时必须指定 accountId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = BotAccountScope.resolveCapability(services,
                    context.arguments(), BotAccountToolSupport.UNSUPPORTED_FRIEND_REQUEST);
            FriendRequestService service = services.get(accountId);
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
            data.put("accountId", accountId);
            data.put("requestId", decision.requestId());
            data.put("status", decision.status());
            data.put("idempotent", decision.idempotent());
            data.put("deliveryState", decision.deliveryState());
            data.put("errorCode", decision.errorCode());
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
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
        for (String name : List.of("accountId", "requestId", "approve", "remark")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
