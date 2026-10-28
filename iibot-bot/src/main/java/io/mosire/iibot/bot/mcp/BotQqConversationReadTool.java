package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.store.ConsumerConversationState;
import io.mosire.iibot.bot.store.ConsumerConversationStore;
import io.mosire.iibot.bot.store.MessageBoardStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 读取 consumer 当前未 ack 的消息批次。
 *
 * <p>只记录服务端 deliveredSeq，不推进 readSeq、不修改 Inbox。未 ack 前重复调用会
 * 返回同一 delivered 批次；read 与 ack 之间新到达的消息留到下一次读取。</p>
 */
public final class BotQqConversationReadTool implements AgentTool {

    public static final String NAME = "qq_conversation_read";
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of("type", "string", "minLength", 1),
                    "limit", Map.of("type", "integer", "minimum", 1,
                            "maximum", MAX_LIMIT,
                            "description", "新批次最多返回条数，默认 50；未 ack 的旧批次回放不受该值截断")),
            "required", List.of("threadId"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final ConsumerConversationStore consumers;
    private final Clock clock;

    public BotQqConversationReadTool(MessageBoardStore board,
                                     ConsumerConversationStore consumers) {
        this(board, consumers, Clock.systemUTC());
    }

    public BotQqConversationReadTool(MessageBoardStore board,
                                     ConsumerConversationStore consumers, Clock clock) {
        this.board = Objects.requireNonNull(board, "board");
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取当前 consumer 尚未显式 ack 的会话消息批次；只预览并记录服务端投递位置，"
                + "不推进已读游标、不归档 Inbox。处理完请调用 qq_conversation_ack(threadId)。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.requiredText(
                    context.arguments(), "threadId", 512);
            int limit = BotToolSupport.optionalInt(
                    context.arguments(), "limit", DEFAULT_LIMIT);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "limit 必须在 1.." + MAX_LIMIT);
            }

            Optional<ChatThread> thread = board.thread(threadId);
            ConsumerConversationState state = consumers.find(
                    ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId).orElse(null);
            long readSeq = state == null ? 0L : state.readSeq();
            long deliveredSeq = state == null ? 0L : state.deliveredSeq();

            List<ChatMessage> messages;
            if (deliveredSeq > readSeq) {
                // 未 ack 前回放同一 delivered 批次。必须用最大窗口读取，否则调用方用更小
                // limit 重读时只看到批次前缀却 ack 整个 deliveredSeq。
                long replayUpperBound = deliveredSeq;
                messages = board.readThread(threadId, readSeq, MAX_LIMIT).stream()
                        .takeWhile(message -> message.localSeq() <= replayUpperBound)
                        .toList();
            } else {
                messages = board.readThread(threadId, readSeq, limit);
                if (!messages.isEmpty()) {
                    long batchDelivered = messages.getLast().localSeq();
                    state = consumers.recordDelivered(
                            ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                            threadId, batchDelivered, clock.instant());
                    deliveredSeq = state.deliveredSeq();
                }
            }

            List<Map<String, Object>> messageMaps = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                messageMaps.add(BotToolSupport.messageMap(
                        message, board.segments(message.messageKey())));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("threadId", threadId);
            data.put("accountId", thread.map(value -> value.source().accountId())
                    .orElse(""));
            data.put("readSeq", readSeq);
            data.put("deliveredSeq", deliveredSeq);
            data.put("count", messageMaps.size());
            data.put("messages", messageMaps);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("CONVERSATION_READ_UNAVAILABLE",
                    "会话读取暂不可用（内部原因已脱敏）");
        }
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
