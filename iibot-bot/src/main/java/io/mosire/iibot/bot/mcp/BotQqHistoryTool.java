package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.InboundMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 直接读取 OneBot 历史扩展；与 bot_thread_history 的本地权威板分开。 */
public final class BotQqHistoryTool implements AgentTool {

    public static final String NAME = "bot_qq_history";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "chatType", Map.of("type", "string", "enum", List.of("PRIVATE", "GROUP")),
                    "targetId", Map.of("type", "string", "minLength", 1,
                            "description", "私聊 QQ 或群号"),
                    "cursor", Map.of("type", "string",
                            "description", "OneBot message_seq 游标；省略表示最新"),
                    "count", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "单页条数，默认 20"),
                    "reverseOrder", Map.of("type", "boolean",
                            "description", "是否向过去翻页，默认 true")),
            "required", List.of("chatType", "targetId"),
            "additionalProperties", false);

    private final ChatHistoryProvider provider;

    public BotQqHistoryTool(ChatHistoryProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "直接读取 OneBot/LLBot 历史扩展；返回 message_seq 游标和消息段，不写入本地消息板。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String chatType = BotToolSupport.requiredText(
                    context.arguments(), "chatType", 32).toUpperCase(Locale.ROOT);
            InboundMessage.SessionType sessionType;
            try {
                sessionType = InboundMessage.SessionType.valueOf(chatType);
            } catch (IllegalArgumentException invalid) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "chatType 必须是 PRIVATE 或 GROUP");
            }
            String targetId = BotToolSupport.requiredText(
                    context.arguments(), "targetId", 128);
            String cursor = BotToolSupport.optionalText(
                    context.arguments(), "cursor", 128);
            int count = BotToolSupport.optionalInt(context.arguments(), "count", 20);
            boolean reverseOrder = BotToolSupport.optionalBoolean(
                    context.arguments(), "reverseOrder", true);
            if (count < 1 || count > 100) {
                throw new BotToolSupport.InvalidArgumentsException("count 必须在 1..100");
            }
            ChatHistoryPage page = provider.history(new ChatHistoryQuery(
                            sessionType, targetId, cursor.isEmpty() ? null : cursor,
                            count, reverseOrder))
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            List<Map<String, Object>> messages = page.messages().stream()
                    .map(BotQqHistoryTool::row).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("chatType", sessionType.name());
            data.put("targetId", targetId);
            data.put("cursor", cursor);
            data.put("nextCursor", page.nextCursor());
            data.put("exhausted", page.exhausted());
            data.put("count", messages.size());
            data.put("messages", messages);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return BotToolSupport.failure("QQ_HISTORY_UNAVAILABLE",
                    "OneBot 历史读取暂不可用（内部原因已脱敏）");
        }
    }

    private static Map<String, Object> row(ChatHistoryMessage message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("messageId", message.messageId());
        row.put("senderId", message.senderId());
        row.put("senderName", message.senderName());
        row.put("sentAt", message.sentAt() == null ? null : message.sentAt().toString());
        row.put("text", message.text());
        row.put("segments", message.segments().stream()
                .map(BotToolSupport::segmentMap).toList());
        return row;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("chatType", "targetId", "cursor", "count", "reverseOrder")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
