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
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址，如 qq_group/bot-1/955432692"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source"),
                    "limit", Map.of("type", "integer", "minimum", 1,
                            "maximum", MAX_LIMIT,
                            "description", "新批次最多返回条数，默认 50；未 ack 的旧批次回放不受该值截断"),
                    "includeOutbound", Map.of("type", "boolean",
                            "description", "是否把同批 OUT 消息放入 outbound；默认 false，且不进入 ack 边界")),
            "required", List.of(),
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
        return "读取当前 consumer 尚未显式 ack 的入站消息批次；默认不返回 OUT，OUT 只可通过 "
                + "includeOutbound 单独查看。只预览并记录服务端投递位置，不推进已读游标、"
                + "不归档 Inbox。处理完请调用 qq_conversation_ack(source)。";
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
            boolean includeOutbound = BotToolSupport.optionalBoolean(
                    context.arguments(), "includeOutbound", false);
            ConsumerConversationState state = consumers.find(
                    ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId).orElse(null);
            long readSeq = state == null ? 0L : state.readSeq();
            long deliveredSeq = state == null ? 0L : state.deliveredSeq();

            List<ChatMessage> fetched;
            if (deliveredSeq > readSeq) {
                // 未 ack 前回放同一 delivered 批次；新消息不进入本批。
                long replayUpperBound = deliveredSeq;
                fetched = board.readThread(threadId, readSeq, MAX_LIMIT).stream()
                        .takeWhile(message -> message.localSeq() <= replayUpperBound)
                        .toList();
            } else {
                fetched = board.readThread(threadId, readSeq, limit);
            }

            List<ChatMessage> inbound = fetched.stream()
                    .filter(message -> message.direction() == ChatDirection.IN)
                    .toList();
            List<ChatMessage> outbound = includeOutbound
                    ? fetched.stream().filter(message -> message.direction() == ChatDirection.OUT).toList()
                    : List.of();
            if (deliveredSeq <= readSeq && !inbound.isEmpty()) {
                long batchDelivered = inbound.getLast().localSeq();
                state = consumers.recordDelivered(
                        ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                        threadId, batchDelivered, clock.instant());
                deliveredSeq = state.deliveredSeq();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", key.sourceAddress().canonical());
            data.put("threadId", threadId);
            data.put("accountId", key.accountId());
            data.put("readSeq", readSeq);
            data.put("deliveredSeq", deliveredSeq);
            data.put("count", inbound.size());
            data.put("messages", maps(inbound));
            data.put("outboundCount", outbound.size());
            data.put("outbound", maps(outbound));
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("CONVERSATION_READ_UNAVAILABLE",
                    "会话读取暂不可用（内部原因已脱敏）");
        }
    }

    private List<Map<String, Object>> maps(List<ChatMessage> messages) {
        List<Map<String, Object>> values = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            values.add(BotToolSupport.messageMap(
                    message, board.segments(message.messageKey())));
        }
        return values;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("source", "threadId", "limit", "includeOutbound")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
