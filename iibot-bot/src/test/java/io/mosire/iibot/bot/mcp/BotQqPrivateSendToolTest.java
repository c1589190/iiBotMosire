package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplyPort;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotQqPrivateSendToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void createsPrivateThreadPersistsOutboundAndSendsByAccount() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        AtomicReference<ChatSourceKey> sentSource = new AtomicReference<>();
        BotReplyService replies = new BotReplyService(board,
                new CapturingPort(sentSource), CLOCK);
        BotQqPrivateSendTool tool = new BotQqPrivateSendTool(replies, List.of("bot-a"));

        ToolResult result = execute(tool, Map.of(
                "targetQqId", "777",
                "text", "主动私聊",
                "idempotencyKey", "private-1"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("source").asText()).isEqualTo("qq_private/bot-a/777");
        assertThat(data.path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(sentSource.get()).isEqualTo(ChatSourceKey.privateChat("bot-a", "777"));
        assertThat(board.thread(ChatSourceKey.privateChat("bot-a", "777").threadId()))
                .isPresent();
        ChatMessage stored = board.find(ChatMessage.outboundKey(
                        ChatSourceKey.privateChat("bot-a", "777"), "private-1"))
                .orElseThrow();
        assertThat(stored.direction()).isEqualTo(ChatDirection.OUT);
        assertThat(stored.deliveryState()).isEqualTo(DeliveryState.SENT);
        assertThat(stored.text()).isEqualTo("主动私聊");
    }

    @Test
    void requiresAccountWhenMultipleProvidersAreKnown() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        BotReplyService replies = new BotReplyService(board,
                new CapturingPort(new AtomicReference<>()), CLOCK);
        BotQqPrivateSendTool tool = new BotQqPrivateSendTool(
                replies, List.of("bot-a", "bot-b"));

        ToolResult result = execute(tool, Map.of(
                "targetQqId", "777", "text", "你好"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ACCOUNT_REQUIRED");
    }

    private static ToolResult execute(BotQqPrivateSendTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }

    private static final class CapturingPort implements BotReplyPort {
        private final AtomicReference<ChatSourceKey> sentSource;

        private CapturingPort(AtomicReference<ChatSourceKey> sentSource) {
            this.sentSource = sentSource;
        }

        @Override
        public BotReplySendResult send(ChatSourceKey source, String text,
                                       String replyToMessageId, String idempotencyKey) {
            sentSource.set(source);
            return new BotReplySendResult(DeliveryState.SENT, List.of("qq-sent-1"), "");
        }
    }
}
