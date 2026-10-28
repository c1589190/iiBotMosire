package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.friend.FriendRequestRecord;
import io.mosire.iibot.bot.friend.FriendRequestService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按账号列出待处理好友申请，不返回 raw flag。 */
public final class BotFriendRequestListTool implements AgentTool {

    public static final String NAME = "qq_friend_request_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "最多返回条数，默认 50")),
            "additionalProperties", false);

    private final Map<String, FriendRequestService> services;

    /**
     * @param services accountId → 该账号的好友申请服务；value 为 null 表示该账号不支持好友
     *                 申请能力
     */
    public BotFriendRequestListTool(Map<String, FriendRequestService> services) {
        Objects.requireNonNull(services, "services");
        if (services.isEmpty()) {
            throw new IllegalArgumentException("services 不能为空");
        }
        this.services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /** 单账号便捷构造。 */
    public BotFriendRequestListTool(String accountId, FriendRequestService service) {
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
        return "列出某个 Bot 账号的待处理 OneBot 入站好友申请；返回 requestId，不返回 raw "
                + "flag。多账号时必须指定 accountId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = BotAccountScope.resolveCapability(services,
                    context.arguments(), BotAccountToolSupport.UNSUPPORTED_FRIEND_REQUEST);
            FriendRequestService service = services.get(accountId);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (limit < 1 || limit > 200) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..200");
            }
            List<Map<String, Object>> rows = service.listPending(limit).stream()
                    .map(BotFriendRequestListTool::row).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("count", rows.size());
            data.put("requests", rows);
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("FRIEND_REQUEST_UNAVAILABLE",
                    "好友申请读取暂不可用（内部原因已脱敏）");
        }
    }

    private static Map<String, Object> row(FriendRequestRecord request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("requestId", request.requestId());
        row.put("userId", request.userId());
        row.put("displayName", request.displayName());
        row.put("comment", request.comment());
        row.put("receivedAt", request.receivedAt().toString());
        row.put("status", request.status());
        return row;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
