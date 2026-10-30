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
 * 确认 source 当前已投递的入站批次已处理。
 *
 * <p>不要求先 read；未 read 时归档当前 active Inbox item。已 read 时只推进 read 时固定的
 * delivered 批次，read 与 ack 之间到达的新消息保留下一次未读。</p>
 */
public final class BotQqConversationAckTool implements AgentTool {

    public static final String NAME = "qq_conversation_ack";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址")),
            "required", List.of("source"),
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
        return "确认 source 当前已投递批次处理完毕。返回 more=true 表示同一 active item "
                + "还有未读，应在同一回合继续 qq_conversation_read(source)。未 read 时"
                + "调用会直接消费当前 active item；若只想消费已看到的批次，应先 read。"
                + "若 wake 的 kind=digest_complete 或 complete=true，且没有"
                + " truncated=true，可直接调用本工具 ack(source)，不需要先 read；"
                + "truncated=true 时摘要已截断，必须先 read。";
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
            if (hasDeliveredBatch) {
                ackedSeq = found.orElseThrow().deliveredSeq();
                inboxAck = inboxDigests.ackThrough(threadId, ackedSeq);
            } else {
                inboxAck = inboxDigests.ackCurrent(threadId);
                ackedSeq = inboxAck.digestedThroughSeq();
            }

            if (ackedSeq > 0L) {
                consumers.recordDelivered(
                        ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId,
                        ackedSeq, clock.instant());
                consumers.advanceRead(
                        ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId,
                        ackedSeq, clock.instant());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("more", inboxAck.remainingToSeq() > 0L);
            return ToolResult.ok(BotToolSupport.json(data));
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
        if (context.arguments().containsKey("source")) {
            safe.put("source", context.arguments().get("source"));
        }
        return Map.copyOf(safe);
    }
}
