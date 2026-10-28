package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.inbox.InMemoryInboxDigestAuditStore;
import io.mosire.iibot.bot.inbox.InboxDigestAudit;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.ForwardMessage;
import io.mosire.iibot.connect.core.ForwardPage;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.RecentQqMessage;
import io.mosire.iibot.connect.core.RecentQqProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BotAdvancedToolsTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void threadHistoryReadsBackwardsAndReportsNextCursor() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        for (int i = 1; i <= 3; i++) {
            board.append(inbound("m-" + i, ""));
        }

        JsonNode result = call(new BotThreadHistoryTool(board), Map.of(
                "threadId", GROUP.threadId(), "limit", 2));

        assertThat(result.path("data").path("count").asInt()).isEqualTo(2);
        assertThat(result.path("data").path("hasMore").asBoolean()).isTrue();
        assertThat(result.path("data").path("nextBeforeSeq").asLong()).isEqualTo(2L);
        assertThat(result.path("data").path("messages").get(0).path("localSeq").asLong())
                .isEqualTo(2L);
        assertThat(result.path("data").path("messages").get(1).path("localSeq").asLong())
                .isEqualTo(3L);
    }

    @Test
    void quoteThreadFollowsReplyToMessageId() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage parent = board.append(inbound("m-1", "")).message();
        ChatMessage child = board.append(inbound("m-2", parent.messageId())).message();

        JsonNode result = call(new BotMessageThreadTool(board),
                Map.of("messageKey", child.messageKey(), "depth", 3));

        assertThat(result.path("data").path("count").asInt()).isEqualTo(2);
        assertThat(result.path("data").path("messages").get(0).path("messageId").asText())
                .isEqualTo("m-1");
        assertThat(result.path("data").path("messages").get(1).path("messageId").asText())
                .isEqualTo("m-2");
    }

    @Test
    void forwardToolReadsBoundedForwardSegment() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = inbound("m-1", "");
        board.append(message, List.of(MessageSegment.forward(0, "forward-1")));
        ForwardCapability provider = (id, limit) -> CompletableFuture.completedFuture(
                new ForwardPage(List.of(new ForwardMessage("qq-2", "Bob", T0,
                        "转发正文", List.of(MessageSegment.text(0, "转发正文")))), false));

        JsonNode result = call(new BotForwardReadTool(board, provider),
                Map.of("messageKey", message.messageKey(), "depth", 1, "limit", 10));

        assertThat(result.path("data").path("segmentIndex").asInt()).isZero();
        assertThat(result.path("data").path("messages")).hasSize(1);
        assertThat(result.path("data").path("messages").get(0).path("text").asText())
                .isEqualTo("转发正文");
    }


    @Test
    void forwardToolRoutesByMessageAccount() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatSourceKey accountB = ChatSourceKey.groupChat("bot-b", "group-b");
        ChatMessage message = ChatMessage.inbound(accountB, "m-b", "qq-b", "Bob",
                T0, T0, "", "", "");
        board.append(message, List.of(MessageSegment.forward(0, "forward-b")));
        AtomicInteger providerACalls = new AtomicInteger();
        ForwardCapability providerA = (id, limit) -> {
            providerACalls.incrementAndGet();
            return CompletableFuture.completedFuture(new ForwardPage(List.of(), false));
        };
        ForwardCapability providerB = (id, limit) -> CompletableFuture.completedFuture(
                new ForwardPage(List.of(new ForwardMessage("qq-b", "Bob", T0,
                        "B 的转发", List.of(MessageSegment.text(0, "B 的转发")))),
                        false));

        JsonNode result = call(new BotForwardReadTool(board,
                        Map.of("bot-a", providerA, "bot-b", providerB)),
                Map.of("messageKey", message.messageKey(), "depth", 1, "limit", 10));

        assertThat(result.path("data").path("messages").get(0).path("text").asText())
                .isEqualTo("B 的转发");
        assertThat(providerACalls).hasValue(0);
    }

    @Test
    void qqHistoryToolUsesProviderCursor() throws Exception {
        ChatHistoryProvider provider = query -> CompletableFuture.completedFuture(
                new ChatHistoryPage(List.of(new ChatHistoryMessage(
                        "m-1", "qq-2", "Bob", "历史正文", T0,
                        List.of(MessageSegment.text(0, "历史正文")))),
                        "100", false, 0));

        JsonNode result = call(new BotQqHistoryTool(provider), Map.of(
                "chatType", "GROUP", "targetId", "group-1", "count", 20));

        assertThat(result.path("data").path("nextCursor").asText()).isEqualTo("100");
        assertThat(result.path("data").path("messages").get(0).path("text").asText())
                .isEqualTo("历史正文");
    }

    @Test
    void inboxHistoryToolReturnsAuditSummaryWithoutBody() throws Exception {
        InMemoryInboxDigestAuditStore audit = new InMemoryInboxDigestAuditStore();
        audit.upsert(new InboxDigestAudit("thread:1", "itm-1", 1, 2,
                "通知摘要", 0, "done", 2, T0, T0, T0));

        JsonNode result = call(new BotInboxHistoryTool(audit), Map.of("limit", 10));

        assertThat(result.path("data").path("items")).hasSize(1);
        assertThat(result.path("data").path("items").get(0).path("summary").asText())
                .isEqualTo("通知摘要");
        assertThat(result.path("data").path("items").get(0).path("itemId").asText())
                .isEqualTo("itm-1");
    }

    @Test
    void qqRecentToolIsDistinctFromInbox() throws Exception {
        RecentQqProvider provider = limit -> CompletableFuture.completedFuture(List.of(
                new RecentQqMessage(InboundMessage.SessionType.PRIVATE, "qq-2", "Bob",
                        new ChatHistoryMessage("m-1", "qq-2", "Bob", "客户端最近消息", T0)),
                new RecentQqMessage(InboundMessage.SessionType.GROUP, "group-3", "群",
                        new ChatHistoryMessage("m-2", "qq-3", "Alice", "群最近消息", T0))));

        JsonNode result = call(new BotQqRecentTool(provider), Map.of(
                "limit", 10, "includePrivate", true, "includeGroups", false));

        assertThat(result.path("data").path("source").asText())
                .isEqualTo("onebot-client-recent");
        assertThat(result.path("data").path("count").asInt()).isEqualTo(1);
        assertThat(result.path("data").path("recent").get(0).path("sessionType").asText())
                .isEqualTo("PRIVATE");
    }

    private static ChatMessage inbound(String messageId, String replyTo) {
        return ChatMessage.inbound(GROUP, messageId, "qq-1", "Alice", T0, T0,
                "正文-" + messageId, "", replyTo);
    }

    private static JsonNode call(AgentTool tool, Map<String, Object> args) throws Exception {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
        assertThat(result.success()).isTrue();
        return JSON.readTree(result.message());
    }
}
