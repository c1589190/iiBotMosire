package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.inbox.InboxDigestException;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.store.ConsumerConversationState;
import io.mosire.iibot.bot.store.ConsumerConversationStore;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 显式确认 consumer 已处理到服务端最近 deliveredSeq。
 *
 * <p>Agent 只传 threadId。服务端先同步 Inbox，再推进 consumer readSeq；read 与 ack
 * 之间到达的新消息保持未读。重复 ack 为稳定 no-op。</p>
 */
public final class BotQqConversationAckTool implements AgentTool {

    public static final String NAME = "qq_conversation_ack";
    public static final String NO_DELIVERED_REASON = "NO_DELIVERED_MESSAGES";
    public static final String NO_ACTIVE_REASON = "NO_ACTIVE_ITEM";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source"),
                    "seq", Map.of("type", "integer", "minimum", 1,
                            "description", "可选消息序号；显式 ack 到该序号，不需要额外不透明 ID")),
            "required", List.of(),
            "additionalProperties", false);

    private final ConsumerConversationStore consumers;
    private final InboxDigestService inboxDigests;
    private final Clock clock;

    public BotQqConversationAckTool(ConsumerConversationStore consumers,
                                    InboxDigestService inboxDigests) {
        this(consumers, inboxDigests, Clock.systemUTC());
    }

    public BotQqConversationAckTool(ConsumerConversationStore consumers,
                                    InboxDigestService inboxDigests, Clock clock) {
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.inboxDigests = Objects.requireNonNull(inboxDigests, "inboxDigests");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "确认已处理当前会话。若之前 read 过，按 read 时固定的 delivered 批次归档；"
                + "若没有 read，直接归档当前 active Inbox item（Inbox 摘要已足够时无需先 read）。"
                + "只接受 source/threadId，不接受 revision、itemId、snapshot 或 seq。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.threadKey(context.arguments()).threadId();
            Optional<ConsumerConversationState> found = consumers.find(
                    ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId);
            boolean hasDeliveredBatch = found.isPresent()
                    && found.orElseThrow().deliveredSeq() > found.orElseThrow().readSeq();
            InboxDigestService.AckResult inboxAck;
            long ackedSeq;
            String mode;
            if (context.arguments().containsKey("seq")) {
                ackedSeq = BotToolSupport.optionalLong(context.arguments(), "seq", 0L);
                if (ackedSeq <= 0L) {
                    throw new BotToolSupport.InvalidArgumentsException("seq 必须为正");
                }
                inboxAck = inboxDigests.ackThrough(threadId, ackedSeq);
                mode = "EXPLICIT_SEQ";
            } else if (hasDeliveredBatch) {
                ackedSeq = found.orElseThrow().deliveredSeq();
                inboxAck = inboxDigests.ackThrough(threadId, ackedSeq);
                mode = "DELIVERED_BATCH";
            } else {
                inboxAck = inboxDigests.ackCurrent(threadId);
                ackedSeq = inboxAck.digestedThroughSeq();
                mode = "ACTIVE_ITEM";
                if (!inboxAck.archived() && inboxAck.remainingToSeq() == 0L
                        && ackedSeq == 0L) {
                    ConsumerConversationState state = found.orElse(null);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("source", BotToolSupport.sourceOf(threadId));
                    data.put("threadId", threadId);
                    data.put("acked", false);
                    data.put("reason", NO_ACTIVE_REASON);
                    data.put("readSeq", state == null ? 0L : state.readSeq());
                    data.put("deliveredSeq", state == null ? 0L : state.deliveredSeq());
                    data.put("inboxArchived", false);
                    data.put("remaining", false);
                    return BotToolSupport.ok(NAME, data);
                }
            }
            ConsumerConversationState advanced = found.orElse(null);
            if (ackedSeq > 0L) {
                advanced = consumers.recordDelivered(
                        ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId,
                        ackedSeq, clock.instant());
                advanced = consumers.advanceRead(
                        ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId,
                        ackedSeq, clock.instant());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", BotToolSupport.sourceOf(threadId));
            data.put("threadId", threadId);
            data.put("acked", true);
            data.put("reason", "ACKED");
            data.put("mode", mode);
            data.put("readSeq", advanced == null ? 0L : advanced.readSeq());
            data.put("deliveredSeq", advanced == null ? 0L : advanced.deliveredSeq());
            data.put("inboxArchived", inboxAck.archived());
            data.put("remaining", inboxAck.remainingToSeq() > 0L);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (InboxDigestException failure) {
            return BotToolSupport.failure(
                    failure.code(), failure.getMessage(), failure.details());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("CONVERSATION_ACK_UNAVAILABLE",
                    "会话 ack 暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (context.arguments().containsKey("threadId")) {
            safe.put("threadId", context.arguments().get("threadId"));
        }
        return Map.copyOf(safe);
    }
}
