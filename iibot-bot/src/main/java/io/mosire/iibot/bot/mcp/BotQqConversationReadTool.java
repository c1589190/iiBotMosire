package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.ConsumerConversationState;
import io.mosire.iibot.bot.store.ConsumerConversationStore;
import io.mosire.iibot.bot.store.MessageBoardStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 读取 source 当前未 ack 的入站消息批次。
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
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址")),
            "required", List.of("source"),
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
        return "读取 source 的未读入站消息；返回 compact 消息和 more 标记。"
                + "complete=true 的 Inbox 摘要可直接 ack，不需要先 read。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            ChatSourceKey key = BotToolSupport.threadKey(context.arguments());
            String threadId = key.threadId();
            int limit = BotToolSupport.optionalInt(
                    context.arguments(), "limit", DEFAULT_LIMIT);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "limit 必须在 1.." + MAX_LIMIT);
            }
            ConsumerConversationState state = consumers.find(
                    ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId).orElse(null);
            long readSeq = state == null ? 0L : state.readSeq();
            long deliveredSeq = state == null ? 0L : state.deliveredSeq();

            boolean replay = deliveredSeq > readSeq;
            long replayUpperBound = replay ? deliveredSeq : Long.MAX_VALUE;
            // A replayed delivered batch is all-or-nothing: ack uses deliveredSeq,
            // so returning only a prefix would let ack consume messages the model
            // never saw. New batches are bounded and advertise more instead.
            int fetchLimit = replay ? 1000 : limit + 1;
            List<ChatMessage> fetched = board.readThread(threadId, readSeq, fetchLimit);
            List<ChatMessage> inbound = new ArrayList<>();
            for (ChatMessage message : fetched) {
                if (message.localSeq() > replayUpperBound) break;
                if (message.direction() == ChatDirection.IN) {
                    inbound.add(message);
                }
            }
            boolean more = !replay && inbound.size() > limit;
            if (more) {
                inbound = new ArrayList<>(inbound.subList(0, limit));
            }
            if (!replay && !inbound.isEmpty()) {
                long batchDelivered = inbound.getLast().localSeq();
                state = consumers.recordDelivered(
                        ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                        threadId, batchDelivered, clock.instant());
                deliveredSeq = state.deliveredSeq();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", key.sourceAddress().canonical());
            data.put("messages", compactMaps(inbound));
            data.put("more", more);
            return ToolResult.ok(BotToolSupport.json(data));
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("CONVERSATION_READ_UNAVAILABLE",
                    "会话读取暂不可用（内部原因已脱敏）");
        }
    }

    private List<Map<String, Object>> compactMaps(List<ChatMessage> messages) {
        List<Map<String, Object>> values = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            values.add(BotToolSupport.compactMessageMap(
                    message, board.segments(message.messageKey())));
        }
        return values;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (context.arguments().containsKey("source")) {
            safe.put("source", context.arguments().get("source"));
        }
        return Map.copyOf(safe);
    }
}
