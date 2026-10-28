package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按 thread 读取完整消息正文，用于主 Agent 恢复上下文。 */
public final class BotThreadReadTool implements AgentTool {

    public static final String NAME = "bot_thread_read";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of("type", "string", "minLength", 1),
                    "afterSeq", Map.of("type", "integer", "minimum", 0,
                            "description", "严格读取大于该 localSeq 的消息，默认 0"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 1000,
                            "description", "最多返回条数，默认 50")),
            "required", List.of("threadId"),
            "additionalProperties", false);

    private final MessageBoardStore board;

    public BotThreadReadTool(MessageBoardStore board) {
        this.board = Objects.requireNonNull(board, "board");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取一个来源 thread 的完整消息正文；按 localSeq 升序，支持 afterSeq 分页。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.requiredText(context.arguments(), "threadId", 512);
            long afterSeq = BotToolSupport.optionalLong(context.arguments(), "afterSeq", 0L);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (afterSeq < 0L) {
                throw new BotToolSupport.InvalidArgumentsException("afterSeq 不能为负");
            }
            if (limit < 1 || limit > 1000) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..1000");
            }
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ChatMessage message : board.readThread(threadId, afterSeq, limit)) {
                messages.add(BotToolSupport.messageMap(message,
                        board.segments(message.messageKey())));
            }
            long lastSeq = messages.isEmpty()
                    ? afterSeq : ((Number) messages.getLast().get("localSeq")).longValue();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", BotToolSupport.sourceOf(threadId));
            data.put("threadId", threadId);
            data.put("afterSeq", afterSeq);
            data.put("lastSeq", lastSeq);
            data.put("count", messages.size());
            data.put("messages", messages);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("THREAD_READ_UNAVAILABLE",
                    "消息板 thread 读取暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("threadId", "afterSeq", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
