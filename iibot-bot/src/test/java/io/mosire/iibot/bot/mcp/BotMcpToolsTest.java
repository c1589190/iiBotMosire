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
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.inbox.InboxDigest;
import io.mosire.iibot.bot.inbox.InboxDigestItem;
import io.mosire.iibot.bot.inbox.InboxDigestPort;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.inbox.InboxDigestState;
import io.mosire.iibot.bot.inbox.InMemoryInboxDigestStateStore;
import io.mosire.iibot.bot.note.InMemoryUserNoteStore;
import io.mosire.iibot.bot.reply.BotReplyPort;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.MessageSegment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BotMcpToolsTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exposesThreadMessageReplyAndNoteTools() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "第一句", "[图片×1]", ""));
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        FakeInboxPort inboxPort = new FakeInboxPort();
        InboxDigestService digestService = new InboxDigestService(board, notes,
                new InMemoryInboxDigestStateStore(), inboxPort);
        BotReplyService replyService = new BotReplyService(board,
                new FakeReplyPort(), CLOCK);
        List<AgentTool> tools = BotMcpTools.all(board, notes, digestService, replyService, "bot-1");
        assertThat(tools).extracting(AgentTool::name).containsExactly(
                "bot_thread_list", "bot_thread_read", "bot_thread_history",
                "bot_message_get", "bot_message_thread", "bot_reply",
                "bot_thread_ack", "bot_user_note_get", "bot_user_note_list",
                "bot_user_note_upsert");

        JsonNode listed = call(tool(tools, "bot_thread_list"), Map.of());
        assertThat(listed.path("data").path("threads")).hasSize(1);
        String threadId = listed.path("data").path("threads").get(0).path("threadId").asText();

        JsonNode read = call(tool(tools, "bot_thread_read"),
                Map.of("threadId", threadId, "limit", 10));
        assertThat(read.path("data").path("messages")).hasSize(1);
        assertThat(read.path("data").path("messages").get(0).path("text").asText())
                .isEqualTo("第一句");
        assertThat(read.path("data").path("messages").get(0).path("nonTextSummary").asText())
                .isEqualTo("[图片×1]");

        JsonNode message = call(tool(tools, "bot_message_get"),
                Map.of("messageKey", ChatMessage.inboundKey(GROUP, "m-1")));
        assertThat(message.path("data").path("senderName").asText()).isEqualTo("Alice");

        JsonNode replied = call(tool(tools, "bot_reply"), Map.of(
                "threadId", threadId,
                "text", "收到",
                "idempotencyKey", "reply-1"));
        assertThat(replied.path("data").path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(replied.path("data").path("alreadyAttempted").asBoolean()).isFalse();
        assertThat(board.readThread(threadId, 1L, 10)).hasSize(1);

        JsonNode replay = call(tool(tools, "bot_reply"), Map.of(
                "threadId", threadId,
                "text", "收到",
                "idempotencyKey", "reply-1"));
        assertThat(replay.path("data").path("alreadyAttempted").asBoolean()).isTrue();

        JsonNode saved = call(tool(tools, "bot_user_note_upsert"), Map.of(
                "qqId", "qq-1", "tags", List.of("vip"), "note", "重点", "priority", 80));
        assertThat(saved.path("data").path("revision").asLong()).isEqualTo(1L);
        JsonNode fetched = call(tool(tools, "bot_user_note_get"), Map.of("qqId", "qq-1"));
        assertThat(fetched.path("data").path("tags")).hasSize(1);
        JsonNode noteList = call(tool(tools, "bot_user_note_list"),
                Map.of("tag", "vip"));
        assertThat(noteList.path("data").path("notes")).hasSize(1);
    }

    @Test
    void replyWithMentionResolvesQuotedSenderInsideThread() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage quoted = board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "原消息", "", "")).message();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        FakeInboxPort inboxPort = new FakeInboxPort();
        InboxDigestService digestService = new InboxDigestService(board, notes,
                new InMemoryInboxDigestStateStore(), inboxPort);
        FakeReplyPort replyPort = new FakeReplyPort();
        BotReplyService replyService = new BotReplyService(board, replyPort, CLOCK);
        AgentTool reply = new BotReplyTool(replyService);

        JsonNode result = call(reply, Map.of(
                "threadId", GROUP.threadId(),
                "text", "收到",
                "replyToMessageId", quoted.messageId(),
                "mentionSender", true,
                "idempotencyKey", "reply-mention-1"));

        assertThat(result.path("data").path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(replyPort.mentionQqId).isEqualTo("qq-1");
        ChatMessage stored = board.find(ChatMessage.outboundKey(GROUP, "reply-mention-1"))
                .orElseThrow();
        assertThat(stored.replyToMessageId()).isEqualTo("m-1");
        assertThat(stored.nonTextSummary()).contains("[引用×1]", "[@×1]");
        assertThat(board.segments(stored.messageKey()))
                .extracting(MessageSegment::type)
                .containsExactly(MessageSegment.Type.REPLY,
                        MessageSegment.Type.MENTION, MessageSegment.Type.TEXT);
    }

    @Test
    void ackArchivesActiveDigest() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "待处理", "", ""));
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        FakeInboxPort inboxPort = new FakeInboxPort();
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        InboxDigestService digestService = new InboxDigestService(board, notes, states, inboxPort);
        BotMcpTools.all(board, notes, digestService,
                new BotReplyService(board, new FakeReplyPort(), CLOCK), "bot-1");
        InboxDigestService.FlushResult flush = digestService.flushThread(GROUP.threadId());
        AgentTool ack = new BotThreadAckTool(digestService);

        JsonNode result = call(ack, Map.of(
                "threadId", GROUP.threadId(),
                "itemId", flush.itemId(),
                "expectedRevision", states.find(GROUP.threadId()).orElseThrow().revision(),
                "snapshotToSeq", 1));

        assertThat(result.path("data").path("archived").asBoolean()).isTrue();
        assertThat(result.path("data").path("digestedThroughSeq").asLong()).isEqualTo(1L);
        assertThat(result.path("data").path("snapshotToSeq").asLong()).isEqualTo(1L);
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 1L);
        assertThat(inboxPort.archived).isTrue();
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static JsonNode call(AgentTool tool, Map<String, Object> args) throws Exception {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
        return JSON.readTree(result.message());
    }

    private static final class FakeReplyPort implements BotReplyPort {
        private String mentionQqId = "";

        @Override
        public BotReplySendResult send(ChatSourceKey source, String text,
                                       String replyToMessageId, String idempotencyKey) {
            return new BotReplySendResult(DeliveryState.SENT, List.of("qq-sent-1"), "");
        }

        @Override
        public BotReplySendResult sendWithMention(
                ChatSourceKey source, String text, String replyToMessageId,
                String mentionQqId, String idempotencyKey) {
            this.mentionQqId = mentionQqId;
            return send(source, text, replyToMessageId, idempotencyKey);
        }
    }

    private static final class FakeInboxPort implements InboxDigestPort {
        private boolean archived;

        @Override
        public InboxDigestItem push(String dedupeKey, InboxDigest digest) {
            return new InboxDigestItem("itm-1", 1L, "available");
        }

        @Override
        public Optional<InboxDigestItem> view(String itemId) {
            return Optional.of(new InboxDigestItem("itm-1", 1L, "available"));
        }

        @Override
        public InboxDigestItem update(String itemId, long expectedRevision,
                                      InboxDigest digest) {
            return new InboxDigestItem(itemId, expectedRevision + 1L, "available");
        }

        @Override
        public void archive(String itemId, long expectedRevision) {
            archived = true;
        }
    }
}
