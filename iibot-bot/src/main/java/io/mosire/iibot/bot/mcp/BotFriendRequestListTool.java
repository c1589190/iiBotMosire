package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.friend.FriendRequestRecord;
import io.mosire.iibot.bot.friend.FriendRequestService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 列出待处理好友申请，不返回 raw flag。 */
public final class BotFriendRequestListTool implements AgentTool {

    public static final String NAME = "bot_friend_request_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "最多返回条数，默认 50")),
            "additionalProperties", false);

    private final FriendRequestService service;

    public BotFriendRequestListTool(FriendRequestService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "列出待处理 OneBot 入站好友申请；返回 requestId，不返回 raw flag。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (limit < 1 || limit > 200) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..200");
            }
            List<Map<String, Object>> rows = service.listPending(limit).stream()
                    .map(BotFriendRequestListTool::row).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", rows.size());
            data.put("requests", rows);
            return BotToolSupport.ok(NAME, data);
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
        if (context.arguments().containsKey("limit")) {
            safe.put("limit", context.arguments().get("limit"));
        }
        return Map.copyOf(safe);
    }
}
