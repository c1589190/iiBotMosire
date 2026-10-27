package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.store.MessageBoardStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 向后翻页读取 thread 历史；bot_thread_read 继续负责向前读未读上下文。 */
public final class BotThreadHistoryTool implements AgentTool {

    public static final String NAME = "bot_thread_history";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of("type", "string", "minLength", 1),
                    "beforeSeq", Map.of("type", "integer", "minimum", 0,
                            "description", "严格读取小于该 localSeq 的消息；省略则读最新"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "最多返回条数，默认 50")),
            "required", List.of("threadId"),
            "additionalProperties", false);

    private final MessageBoardStore board;

    public BotThreadHistoryTool(MessageBoardStore board) {
        this.board = Objects.requireNonNull(board, "board");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "查看群聊/私聊 thread 的历史消息，支持 beforeSeq 向前翻页；返回按 localSeq 升序。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.requiredText(
                    context.arguments(), "threadId", 512);
            ChatThread thread = board.thread(threadId).orElseThrow(() ->
                    new BotToolSupport.InvalidArgumentsException("thread 不存在"));
            long beforeSeq = BotToolSupport.optionalLong(
                    context.arguments(), "beforeSeq", thread.lastSeq() + 1L);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (beforeSeq < 0L) {
                throw new BotToolSupport.InvalidArgumentsException("beforeSeq 不能为负");
            }
            if (limit < 1 || limit > 200) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..200");
            }
            List<ChatMessage> page = board.readThreadBefore(threadId, beforeSeq, limit + 1);
            boolean hasMore = page.size() > limit;
            if (hasMore) {
                page = page.subList(page.size() - limit, page.size());
            }
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ChatMessage message : page) {
                messages.add(BotToolSupport.messageMap(
                        message, board.segments(message.messageKey())));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("threadId", threadId);
            data.put("beforeSeq", beforeSeq);
            data.put("nextBeforeSeq", page.isEmpty() ? null : page.getFirst().localSeq());
            data.put("hasMore", hasMore);
            data.put("count", messages.size());
            data.put("messages", messages);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("THREAD_HISTORY_UNAVAILABLE",
                    "消息板历史读取暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("threadId", "beforeSeq", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
