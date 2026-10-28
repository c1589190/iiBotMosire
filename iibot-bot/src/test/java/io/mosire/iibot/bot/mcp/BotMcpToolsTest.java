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
import io.mosire.iibot.bot.store.ConsumerConversationState;
import io.mosire.iibot.bot.store.ConsumerConversationStore;
import io.mosire.iibot.bot.store.InMemoryConsumerConversationStore;
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
                "qq_conversation_read", "qq_conversation_ack",
                "bot_user_note_get", "bot_user_note_list", "bot_user_note_upsert");
        assertThat(tools).extracting(AgentTool::name).doesNotContain("bot_thread_ack");

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
    void readIsPreviewOnlyRepeatableAndAckConsumesOnlyDeliveredBatch() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "第一条", "", ""));
        board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                "第二条", "", ""));
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        FakeInboxPort inboxPort = new FakeInboxPort();
        InMemoryInboxDigestStateStore digestStates = new InMemoryInboxDigestStateStore();
        InboxDigestService digestService =
                new InboxDigestService(board, notes, digestStates, inboxPort);
        digestService.flushThread(GROUP.threadId());
        InMemoryConsumerConversationStore consumers = new InMemoryConsumerConversationStore();
        List<AgentTool> tools = BotMcpTools.allForAccounts(
                board, notes, digestService,
                new BotReplyService(board, new FakeReplyPort(), CLOCK), "bot-1",
                null, null, null, null, null, null, consumers);

        JsonNode firstRead = call(tool(tools, "qq_conversation_read"),
                Map.of("threadId", GROUP.threadId(), "limit", 1));
        assertThat(firstRead.path("data").path("accountId").asText()).isEqualTo("bot-1");
        assertThat(firstRead.path("data").path("count").asInt()).isEqualTo(1);
        assertThat(firstRead.path("data").path("messages").get(0).path("text").asText())
                .isEqualTo("第一条");
        assertThat(firstRead.path("data").path("deliveredSeq").asLong()).isEqualTo(1L);
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId())).get()
                .extracting(ConsumerConversationState::readSeq,
                        ConsumerConversationState::deliveredSeq)
                .containsExactly(0L, 1L);
        assertThat(inboxPort.items.get(inboxPort.lastItemId).status())
                .isEqualTo("available");
        assertThat(inboxPort.digests.get(inboxPort.lastItemId).unreadCount())
                .isEqualTo(2);

        JsonNode repeatedRead = call(tool(tools, "qq_conversation_read"),
                Map.of("threadId", GROUP.threadId(), "limit", 1));
        assertThat(repeatedRead.path("data").path("messages")).hasSize(1);
        assertThat(repeatedRead.path("data").path("messages").get(0)
                .path("messageId").asText()).isEqualTo("m-1");
        assertThat(repeatedRead.path("data").path("deliveredSeq").asLong()).isEqualTo(1L);
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId()).orElseThrow().readSeq()).isZero();

        // read 与 ack 之间到达的新消息不能进入上一批，也不能被 ack 吃掉。
        board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                "第三条", "", ""));
        JsonNode ack = call(tool(tools, "qq_conversation_ack"),
                Map.of("threadId", GROUP.threadId()));
        assertThat(ack.path("data").path("acked").asBoolean()).isTrue();
        assertThat(ack.path("data").path("readSeq").asLong()).isEqualTo(1L);
        assertThat(ack.path("data").path("inboxArchived").asBoolean()).isFalse();
        assertThat(ack.path("data").path("remaining").asBoolean()).isTrue();
        assertThat(board.readThread(GROUP.threadId(), 0L, 10))
                .extracting(ChatMessage::localSeq)
                .containsExactly(1L, 2L, 3L);
        assertThat(inboxPort.digests.get(inboxPort.lastItemId).messages())
                .extracting(summary -> summary.localSeq())
                .containsExactly(2L, 3L);

        JsonNode secondRead = call(tool(tools, "qq_conversation_read"),
                Map.of("threadId", GROUP.threadId(), "limit", 10));
        assertThat(secondRead.path("data").path("messages"))
                .extracting(value -> value.path("localSeq").asLong())
                .containsExactly(2L, 3L);
        assertThat(secondRead.path("data").path("deliveredSeq").asLong()).isEqualTo(3L);

        JsonNode secondAck = call(tool(tools, "qq_conversation_ack"),
                Map.of("threadId", GROUP.threadId()));
        assertThat(secondAck.path("data").path("acked").asBoolean()).isTrue();
        assertThat(secondAck.path("data").path("readSeq").asLong()).isEqualTo(3L);
        assertThat(secondAck.path("data").path("inboxArchived").asBoolean()).isTrue();
        assertThat(digestStates.find(GROUP.threadId()).orElseThrow().hasActiveItem())
                .isFalse();

        JsonNode noOp = call(tool(tools, "qq_conversation_ack"),
                Map.of("threadId", GROUP.threadId()));
        assertThat(noOp.path("data").path("acked").asBoolean()).isFalse();
        assertThat(noOp.path("data").path("reason").asText())
                .isEqualTo("NO_DELIVERED_MESSAGES");
    }

    @Test
    void readReplayDoesNotTruncateDeliveredBatch() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        for (int index = 1; index <= 3; index++) {
            board.append(ChatMessage.inbound(GROUP, "m-" + index, "qq-1", "Alice",
                    T0, T0, "消息" + index, "", ""));
        }
        InMemoryConsumerConversationStore consumers = new InMemoryConsumerConversationStore();
        BotQqConversationReadTool read = new BotQqConversationReadTool(
                board, consumers, CLOCK);

        JsonNode first = call(read, Map.of("threadId", GROUP.threadId(), "limit", 3));
        JsonNode replay = call(read, Map.of("threadId", GROUP.threadId(), "limit", 1));

        assertThat(first.path("data").path("count").asInt()).isEqualTo(3);
        assertThat(first.path("data").path("deliveredSeq").asLong()).isEqualTo(3L);
        assertThat(replay.path("data").path("count").asInt()).isEqualTo(3);
        assertThat(replay.path("data").path("deliveredSeq").asLong()).isEqualTo(3L);
        assertThat(replay.path("data").path("messages"))
                .extracting(value -> value.path("localSeq").asLong())
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void ackWithoutActiveInboxItemAdvancesDigestCursorOnly() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "第一条", "", ""));
        board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                "第二条", "", ""));
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        FakeInboxPort inboxPort = new FakeInboxPort();
        InMemoryInboxDigestStateStore digestStates = new InMemoryInboxDigestStateStore();
        InboxDigestService digestService =
                new InboxDigestService(board, notes, digestStates, inboxPort);
        InMemoryConsumerConversationStore consumers = new InMemoryConsumerConversationStore();
        BotQqConversationReadTool read = new BotQqConversationReadTool(
                board, consumers, CLOCK);
        BotQqConversationAckTool ack = new BotQqConversationAckTool(
                consumers, digestService, CLOCK);

        call(read, Map.of("threadId", GROUP.threadId()));
        call(ack, Map.of("threadId", GROUP.threadId()));

        assertThat(inboxPort.items).isEmpty();
        assertThat(digestStates.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 2L);
        InboxDigestService.FlushResult flush = digestService.flushThread(GROUP.threadId());
        assertThat(flush.published()).isFalse();
        assertThat(inboxPort.items).isEmpty();
    }

    @Test
    void qqConversationSchemasExposeNoInternalSynchronizationMarkers() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InboxDigestService digestService = new InboxDigestService(board, notes,
                new InMemoryInboxDigestStateStore(), new FakeInboxPort());
        List<AgentTool> tools = BotMcpTools.allForAccounts(
                board, notes, digestService,
                new BotReplyService(board, new FakeReplyPort(), CLOCK), "bot-1",
                null, null, null, null, null, null,
                new InMemoryConsumerConversationStore());

        AgentTool read = tool(tools, "qq_conversation_read");
        AgentTool ack = tool(tools, "qq_conversation_ack");
        String readSchema = JSON.writeValueAsString(read.jsonSchema());
        String ackSchema = JSON.writeValueAsString(ack.jsonSchema());

        JsonNode readRequired = JSON.valueToTree(read.jsonSchema()).path("required");
        JsonNode ackRequired = JSON.valueToTree(ack.jsonSchema()).path("required");
        assertThat(readRequired.size()).isEqualTo(1);
        assertThat(ackRequired.size()).isEqualTo(1);
        assertThat(readRequired.get(0).asText()).isEqualTo("threadId");
        assertThat(ackRequired.get(0).asText()).isEqualTo("threadId");
        assertThat(JSON.valueToTree(read.jsonSchema()).path("properties")
                .path("limit").path("maximum").asInt()).isEqualTo(100);
        assertThat(readSchema + ackSchema)
                .doesNotContain("expectedRevision", "snapshotToSeq", "itemId",
                        "localSeq", "afterSeq");
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
        private final Map<String, InboxDigestItem> items = new java.util.LinkedHashMap<>();
        private final Map<String, InboxDigest> digests = new java.util.LinkedHashMap<>();
        private long nextItem = 1L;
        private String lastItemId = "";

        @Override
        public InboxDigestItem push(String dedupeKey, InboxDigest digest) {
            String itemId = "itm-" + nextItem++;
            InboxDigestItem item = new InboxDigestItem(
                    itemId, 1L, "available", digest.toSeq(), digest.fromSeq(),
                    digest.priority(), digest);
            items.put(itemId, item);
            digests.put(itemId, digest);
            lastItemId = itemId;
            return item;
        }

        @Override
        public Optional<InboxDigestItem> view(String itemId) {
            return Optional.ofNullable(items.get(itemId));
        }

        @Override
        public InboxDigestItem update(String itemId, long expectedRevision,
                                      InboxDigest digest) {
            InboxDigestItem current = items.get(itemId);
            if (current == null || current.revision() != expectedRevision
                    || !current.available()) {
                throw new io.mosire.iibot.bot.inbox.InboxDigestConflictException(
                        "revision conflict");
            }
            InboxDigestItem next = new InboxDigestItem(itemId,
                    current.revision() + 1L, "available", digest.toSeq(),
                    digest.fromSeq(), digest.priority(), digest);
            items.put(itemId, next);
            digests.put(itemId, digest);
            return next;
        }

        @Override
        public void archive(String itemId, long expectedRevision) {
            InboxDigestItem current = items.get(itemId);
            if (current == null || current.revision() != expectedRevision) {
                throw new io.mosire.iibot.bot.inbox.InboxDigestConflictException(
                        "archive conflict");
            }
            items.put(itemId, new InboxDigestItem(itemId,
                    current.revision() + 1L, "done", current.toSeq(),
                    current.fromSeq(), current.priority(), current.digest()));
        }
    }
}
