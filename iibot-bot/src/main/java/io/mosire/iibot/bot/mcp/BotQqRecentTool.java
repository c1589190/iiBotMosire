package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.RecentQqMessage;
import io.mosire.iibot.connect.core.RecentQqProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 读取 OneBot 客户端侧最近会话消息，独立于 Bot Inbox。 */
public final class BotQqRecentTool implements AgentTool {

    public static final String NAME = "qq_recent_messages";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20,
                            "description", "最多返回最近会话数，默认 10"),
                    "includePrivate", Map.of("type", "boolean",
                            "description", "是否包含私聊，默认 true"),
                    "includeGroups", Map.of("type", "boolean",
                            "description", "是否包含群聊，默认 true")),
            "additionalProperties", false);

    private final RecentQqProvider provider;
    private final String accountId;

    public BotQqRecentTool(RecentQqProvider provider) {
        this(provider, null);
    }

    /**
     * P4 直接 provider 工具仍绑定单一 active account；响应显式带 accountId，避免模型误以为
     * 查询覆盖全部账号。完整多账号化留 P6。
     */
    public BotQqRecentTool(RecentQqProvider provider, String accountId) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.accountId = accountId == null || accountId.isBlank() ? null : accountId.trim();
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return accountId == null
                ? "查看 OneBot 客户端最近会话里的最后一条消息；这是 QQ 客户端通知，不是 Bot Inbox。"
                : "查看当前 active 账号 " + accountId
                        + " 的 OneBot 客户端最近会话；这是 QQ 客户端通知，不是 Bot Inbox。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 10);
            if (limit < 1 || limit > 20) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..20");
            }
            boolean includePrivate = BotToolSupport.optionalBoolean(
                    context.arguments(), "includePrivate", true);
            boolean includeGroups = BotToolSupport.optionalBoolean(
                    context.arguments(), "includeGroups", true);
            List<RecentQqMessage> recent = provider.recentMessages(limit)
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RecentQqMessage message : recent) {
                if (message.sessionType() == InboundMessage.SessionType.PRIVATE
                        && !includePrivate) {
                    continue;
                }
                if (message.sessionType() == InboundMessage.SessionType.GROUP
                        && !includeGroups) {
                    continue;
                }
                rows.add(row(message));
                if (rows.size() >= limit) {
                    break;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if (accountId != null) {
                data.put("accountId", accountId);
            }
            data.put("source", "onebot-client-recent");
            data.put("count", rows.size());
            data.put("recent", rows);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return BotToolSupport.failure("QQ_RECENT_UNAVAILABLE",
                    "OneBot 最近消息读取暂不可用（内部原因已脱敏）");
        }
    }

    private static Map<String, Object> row(RecentQqMessage message) {
        ChatHistoryMessage last = message.lastMessage();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionType", message.sessionType().name());
        row.put("targetId", message.targetId());
        row.put("displayName", message.displayName());
        row.put("messageId", last.messageId());
        row.put("senderId", last.senderId());
        row.put("senderName", last.senderName());
        row.put("sentAt", last.sentAt() == null ? null : last.sentAt().toString());
        row.put("text", last.text());
        row.put("segments", last.segments().stream()
                .map(BotToolSupport::segmentMap).toList());
        return row;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("limit", "includePrivate", "includeGroups")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
