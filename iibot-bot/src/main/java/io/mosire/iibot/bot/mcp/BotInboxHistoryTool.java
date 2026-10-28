package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.inbox.InboxDigestAudit;
import io.mosire.iibot.bot.inbox.InboxDigestAuditStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 查看 Bot 侧 Inbox 通知板审计；不是 OneBot 客户端最近消息。 */
public final class BotInboxHistoryTool implements AgentTool {

    public static final String NAME = "qq_inbox_history";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of("type", "string"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "最多返回通知数，默认 50")),
            "additionalProperties", false);

    private final InboxDigestAuditStore store;

    public BotInboxHistoryTool(InboxDigestAuditStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "查看 Bot 通知板的历史摘要和归档状态；正文仍用 bot_thread_read 恢复。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.optionalText(
                    context.arguments(), "threadId", 512);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (limit < 1 || limit > 200) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..200");
            }
            List<Map<String, Object>> rows = store.list(threadId, limit).stream()
                    .map(BotInboxHistoryTool::row).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("threadId", threadId);
            data.put("count", rows.size());
            data.put("items", rows);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("INBOX_HISTORY_UNAVAILABLE",
                    "通知板历史读取暂不可用（内部原因已脱敏）");
        }
    }

    private static Map<String, Object> row(InboxDigestAudit audit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("threadId", audit.threadId());
        row.put("itemId", audit.itemId());
        row.put("fromSeq", audit.fromSeq());
        row.put("toSeq", audit.toSeq());
        row.put("summary", audit.summary());
        row.put("priority", audit.priority());
        row.put("status", audit.status());
        row.put("revision", audit.revision());
        row.put("createdAt", audit.createdAt().toString());
        row.put("updatedAt", audit.updatedAt().toString());
        row.put("ackedAt", audit.ackedAt() == null ? null : audit.ackedAt().toString());
        return row;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("threadId", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
