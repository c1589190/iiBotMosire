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

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source")),
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
        return "显式确认已处理当前会话的服务端投递批次；服务端同步更新/归档 Inbox 并推进"
                + "已读游标。只接受 threadId，不接受 revision、itemId、snapshot 或 seq。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.threadKey(context.arguments()).threadId();
            Optional<ConsumerConversationState> found = consumers.find(
                    ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId);
            if (found.isEmpty()
                    || found.orElseThrow().deliveredSeq() <= found.orElseThrow().readSeq()) {
                ConsumerConversationState state = found.orElse(null);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("source", BotToolSupport.sourceOf(threadId));
                data.put("threadId", threadId);
                data.put("acked", false);
                data.put("reason", NO_DELIVERED_REASON);
                data.put("readSeq", state == null ? 0L : state.readSeq());
                data.put("deliveredSeq", state == null ? 0L : state.deliveredSeq());
                data.put("inboxArchived", false);
                data.put("remaining", false);
                return BotToolSupport.ok(NAME, data);
            }

            ConsumerConversationState current = found.orElseThrow();
            long deliveredSeq = current.deliveredSeq();
            InboxDigestService.AckResult inboxAck =
                    inboxDigests.ackThrough(threadId, deliveredSeq);
            ConsumerConversationState advanced = consumers.advanceRead(
                    ConsumerConversationStore.DEFAULT_CONSUMER_ID, threadId,
                    deliveredSeq, clock.instant());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", BotToolSupport.sourceOf(threadId));
            data.put("threadId", threadId);
            data.put("acked", true);
            data.put("reason", "ACKED");
            data.put("readSeq", advanced.readSeq());
            data.put("deliveredSeq", advanced.deliveredSeq());
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
