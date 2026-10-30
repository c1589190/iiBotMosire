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
import io.mosire.iibot.connect.core.OutboundSegment;
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
                "qq_conversation_list", "qq_conversation_history",
                "qq_message_get", "qq_message_thread", "qq_conversation_send",
                "qq_conversation_read", "qq_conversation_ack",
                "qq_user_note_get", "qq_user_note_list", "qq_user_note_upsert",
                "qq_private_send");
        assertThat(tools).extracting(AgentTool::name).allMatch(name -> name.startsWith("qq_"));
        assertThat(tools).extracting(AgentTool::name)
                .doesNotContain("bot_thread_read", "bot_thread_ack");

        JsonNode listed = call(tool(tools, "qq_conversation_list"), Map.of());
        assertThat(listed.path("data").path("threads")).hasSize(1);
        assertThat(listed.path("data").path("threads").get(0).path("source").asText())
                .isEqualTo("qq_group/bot-1/group-9");
        String threadId = listed.path("data").path("threads").get(0).path("threadId").asText();

        JsonNode read = call(tool(tools, "qq_conversation_read"),
                Map.of("source", "qq_group/bot-1/group-9", "limit", 10));
        assertThat(read.path("source").asText()).isEqualTo("qq_group/bot-1/group-9");
        assertThat(read.path("messages")).hasSize(1);
        assertThat(read.path("messages").get(0).path("from").asText()).isEqualTo("Alice");
        assertThat(read.path("messages").get(0).path("uid").asText()).isEqualTo("qq-1");
        assertThat(read.path("messages").get(0).path("text").asText()).isEqualTo("第一句");
        assertThat(read.path("messages").get(0).path("summary").asText())
                .isEqualTo("[图片×1]");
        assertThat(read.path("more").asBoolean()).isFalse();
        assertThat(read.has("data")).isFalse();
        assertThat(read.toString()).doesNotContain(
                "messageKey", "threadId", "readSeq", "deliveredSeq", "tool\":");

        JsonNode message = call(tool(tools, "qq_message_get"),
                Map.of("messageKey", ChatMessage.inboundKey(GROUP, "m-1")));
        assertThat(message.path("data").path("senderName").asText()).isEqualTo("Alice");

        JsonNode replied = call(tool(tools, "qq_conversation_send"), Map.of(
                "source", "qq_group/bot-1/group-9",
                "text", "收到",
                "idempotencyKey", "reply-1"));
        assertThat(replied.path("data").path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(replied.path("data").path("alreadyAttempted").asBoolean()).isFalse();
        assertThat(board.readThread(threadId, 1L, 10)).hasSize(1);

        JsonNode replay = call(tool(tools, "qq_conversation_send"), Map.of(
                "source", "qq_group/bot-1/group-9",
                "text", "收到",
                "idempotencyKey", "reply-1"));
        assertThat(replay.path("data").path("alreadyAttempted").asBoolean()).isTrue();
        assertThat(replay.path("data").path("idempotencyKey").asText())
                .isEqualTo("reply-1");

        JsonNode generated = call(tool(tools, "qq_conversation_send"), Map.of(
                "threadId", threadId,
                "text", "服务端生成幂等键"));
        String generatedKey = generated.path("data").path("idempotencyKey").asText();
        assertThat(generated.path("data").path("alreadyAttempted").asBoolean()).isFalse();
        assertThat(generatedKey).isNotBlank();
        java.util.UUID.fromString(generatedKey);

        JsonNode saved = call(tool(tools, "qq_user_note_upsert"), Map.of(
                "qqId", "qq-1", "tags", List.of("vip"), "note", "重点", "priority", 80));
        assertThat(saved.path("data").path("revision").asLong()).isEqualTo(1L);
        JsonNode fetched = call(tool(tools, "qq_user_note_get"), Map.of("qqId", "qq-1"));
        assertThat(fetched.path("data").path("tags")).hasSize(1);
        JsonNode noteList = call(tool(tools, "qq_user_note_list"),
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
    void mentionAllPersistsAtAllSegmentAndRejectsCombinedMentionModes() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "原消息", "", ""));
        FakeReplyPort replyPort = new FakeReplyPort();
        AgentTool reply = new BotReplyTool(
                new BotReplyService(board, replyPort, CLOCK));

        JsonNode result = call(reply, Map.of(
                "threadId", GROUP.threadId(),
                "text", "全员通知",
                "mentionAll", true,
                "idempotencyKey", "mention-all-1"));

        assertThat(result.path("data").path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(replyPort.mentionAll).isTrue();
        ChatMessage stored = board.find(ChatMessage.outboundKey(GROUP, "mention-all-1"))
                .orElseThrow();
        assertThat(stored.nonTextSummary()).contains("[@全体×1]");
        assertThat(board.segments(stored.messageKey()))
                .extracting(MessageSegment::type)
                .containsExactly(MessageSegment.Type.MENTION, MessageSegment.Type.TEXT);
        assertThat(board.segments(stored.messageKey()).getFirst().qqId()).isEqualTo("all");

        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(reply.name())
                .build();
        ToolResult invalid = reply.execute(new ToolContext(AccessToken.DEFAULT,
                permissions, Map.of(), Map.of(
                        "threadId", GROUP.threadId(),
                        "text", "冲突",
                        "replyToMessageId", "m-1",
                        "mentionSender", true,
                        "mentionAll", true,
                        "idempotencyKey", "mention-all-conflict")));
        assertThat(invalid.code()).isEqualTo("INVALID_ARGUMENTS");
    }

    @Test
    void imageAndStickerAttachmentsAreParsedAndPersistedWithoutText() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "原消息", "", ""));
        FakeReplyPort replyPort = new FakeReplyPort();
        AgentTool reply = new BotReplyTool(
                new BotReplyService(board, replyPort, CLOCK));

        JsonNode result = call(reply, Map.of(
                "threadId", GROUP.threadId(),
                "attachments", List.of(
                        Map.of("type", "image", "assetRef", "asset-1",
                                "fileName", "image.png", "mediaType", "image/png"),
                        Map.of("type", "sticker", "faceId", "1")),
                "idempotencyKey", "rich-1"));

        assertThat(result.path("data").path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(replyPort.richAttachments).hasSize(2);
        ChatMessage stored = board.find(ChatMessage.outboundKey(GROUP, "rich-1"))
                .orElseThrow();
        assertThat(stored.text()).isEmpty();
        assertThat(stored.nonTextSummary()).contains("[图片×1]", "[表情×1]");
        assertThat(board.segments(stored.messageKey()))
                .extracting(MessageSegment::type)
                .containsExactly(MessageSegment.Type.IMAGE, MessageSegment.Type.FACE);
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
        assertThat(firstRead.path("source").asText())
                .isEqualTo("qq_group/bot-1/group-9");
        assertThat(firstRead.path("messages")).hasSize(1);
        assertThat(firstRead.path("messages").get(0).path("text").asText())
                .isEqualTo("第一条");
        assertThat(firstRead.path("more").asBoolean()).isTrue();
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
        assertThat(repeatedRead.path("messages")).hasSize(1);
        assertThat(repeatedRead.path("messages").get(0).path("seq").asLong()).isEqualTo(1L);
        assertThat(repeatedRead.path("more").asBoolean()).isFalse();
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId()).orElseThrow().readSeq()).isZero();

        // read 与 ack 之间到达的新消息不能进入上一批，也不能被 ack 吃掉。
        board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                "第三条", "", ""));
        JsonNode ack = call(tool(tools, "qq_conversation_ack"),
                Map.of("source", "qq_group/bot-1/group-9"));
        assertThat(ack.size()).isEqualTo(1);
        assertThat(ack.path("more").asBoolean()).isTrue();
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId()).orElseThrow().readSeq()).isEqualTo(1L);
        assertThat(board.readThread(GROUP.threadId(), 0L, 10))
                .extracting(ChatMessage::localSeq)
                .containsExactly(1L, 2L, 3L);
        assertThat(inboxPort.digests.get(inboxPort.lastItemId).messages())
                .extracting(summary -> summary.localSeq())
                .containsExactly(2L, 3L);

        JsonNode secondRead = call(tool(tools, "qq_conversation_read"),
                Map.of("threadId", GROUP.threadId(), "limit", 10));
        assertThat(secondRead.path("messages"))
                .extracting(value -> value.path("seq").asLong())
                .containsExactly(2L, 3L);
        assertThat(secondRead.path("more").asBoolean()).isFalse();

        JsonNode secondAck = call(tool(tools, "qq_conversation_ack"),
                Map.of("threadId", GROUP.threadId()));
        assertThat(secondAck.path("more").asBoolean()).isFalse();
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId()).orElseThrow().readSeq()).isEqualTo(3L);
        assertThat(digestStates.find(GROUP.threadId()).orElseThrow().hasActiveItem())
                .isFalse();

        JsonNode noOp = call(tool(tools, "qq_conversation_ack"),
                Map.of("threadId", GROUP.threadId()));
        assertThat(noOp.path("more").asBoolean()).isFalse();
    }

    @Test
    void compactReadKeepsSafeMediaMetadataButNoFileIdOrUrl() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1",
                "Alice", T0, T0, "", "[图片×1]", ""),
                List.of(MessageSegment.image(0, "secret-file-id", "https://secret/url",
                        "image/png", 123L))).message();
        BotQqConversationReadTool read = new BotQqConversationReadTool(
                board, new InMemoryConsumerConversationStore(), CLOCK);

        JsonNode result = call(read, Map.of(
                "source", "qq_group/bot-1/group-9"));

        JsonNode segment = result.path("messages").get(0).path("segments").get(0);
        assertThat(segment.path("type").asText()).isEqualTo("IMAGE");
        assertThat(segment.path("mediaType").asText()).isEqualTo("image/png");
        assertThat(segment.path("size").asLong()).isEqualTo(123L);
        assertThat(result.toString()).doesNotContain("secret-file-id", "secret/url");
        assertThat(result.path("messages").get(0).path("key").asText())
                .isEqualTo(message.messageKey());
    }

    @Test
    void ackWithoutReadArchivesCurrentItem() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "第一条", "", ""));
        board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice",
                T0, T0, "第二条", "", ""));
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InboxDigestService digestService = new InboxDigestService(board, notes,
                new InMemoryInboxDigestStateStore(), new FakeInboxPort());
        digestService.flushThread(GROUP.threadId());
        InMemoryConsumerConversationStore consumers = new InMemoryConsumerConversationStore();
        List<AgentTool> tools = BotMcpTools.allForAccounts(
                board, notes, digestService,
                new BotReplyService(board, new FakeReplyPort(), CLOCK), "bot-1",
                null, null, null, null, null, null, consumers);
        AgentTool ack = tool(tools, "qq_conversation_ack");

        JsonNode direct = call(ack, Map.of("source", "qq_group/bot-1/group-9"));
        assertThat(direct.path("more").asBoolean()).isFalse();
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId()).orElseThrow().readSeq()).isEqualTo(2L);

        board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice",
                T0, T0, "第三条", "", ""));
        board.append(ChatMessage.inbound(GROUP, "m-4", "qq-1", "Alice",
                T0, T0, "第四条", "", ""));
        digestService.flushThread(GROUP.threadId());
        JsonNode second = call(ack, Map.of("source", "qq_group/bot-1/group-9"));
        assertThat(second.path("more").asBoolean()).isFalse();
        assertThat(consumers.find(ConsumerConversationStore.DEFAULT_CONSUMER_ID,
                GROUP.threadId()).orElseThrow().readSeq()).isEqualTo(4L);
    }

    @Test
    void readReturnsInboundOnly() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.outbound(GROUP, "out-1", T0, "自己先发的", "", ""));
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "入站消息", "", ""));
        InMemoryConsumerConversationStore consumers = new InMemoryConsumerConversationStore();
        BotQqConversationReadTool read = new BotQqConversationReadTool(
                board, consumers, CLOCK);

        JsonNode normal = call(read, Map.of(
                "source", "qq_group/bot-1/group-9", "limit", 10));
        assertThat(normal.path("messages")).hasSize(1);
        assertThat(normal.path("messages").get(0).path("text").asText()).isEqualTo("入站消息");
        assertThat(normal.path("more").asBoolean()).isFalse();
        assertThat(normal.has("outbound")).isFalse();
        assertThat(normal.has("outboundCount")).isFalse();
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

        assertThat(first.path("messages")).hasSize(3);
        assertThat(first.path("more").asBoolean()).isFalse();
        assertThat(replay.path("messages")).hasSize(3);
        assertThat(replay.path("more").asBoolean()).isFalse();
        assertThat(replay.path("messages"))
                .extracting(value -> value.path("seq").asLong())
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
        AgentTool send = tool(tools, "qq_conversation_send");
        String schema = JSON.writeValueAsString(read.jsonSchema())
                + JSON.writeValueAsString(ack.jsonSchema())
                + JSON.writeValueAsString(send.jsonSchema());

        JsonNode readRequired = JSON.valueToTree(read.jsonSchema()).path("required");
        JsonNode ackRequired = JSON.valueToTree(ack.jsonSchema()).path("required");
        JsonNode sendRequired = JSON.valueToTree(send.jsonSchema()).path("required");
        assertThat(readRequired.size()).isEqualTo(1);
        assertThat(readRequired.get(0).asText()).isEqualTo("source");
        assertThat(ackRequired.size()).isEqualTo(1);
        assertThat(ackRequired.get(0).asText()).isEqualTo("source");
        assertThat(sendRequired.size()).isZero();
        assertThat(JSON.valueToTree(send.jsonSchema()).path("properties")
                .has("attachments")).isTrue();
        assertThat(JSON.valueToTree(read.jsonSchema()).path("properties")
                .has("source")).isTrue();
        assertThat(JSON.valueToTree(ack.jsonSchema()).path("properties")
                .has("source")).isTrue();
        assertThat(JSON.valueToTree(send.jsonSchema()).path("properties")
                .has("source")).isTrue();
        assertThat(JSON.valueToTree(read.jsonSchema()).path("properties")
                .has("limit")).isFalse();
        assertThat(schema).doesNotContain("expectedRevision", "snapshotToSeq", "itemId",
                "localSeq", "afterSeq");
    }

    @Test
    void multiAccountAssemblyRequiresAccountIdForDirectProviderAndNoteTools() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InboxDigestService digestService = new InboxDigestService(board, notes,
                new InMemoryInboxDigestStateStore(), new FakeInboxPort());
        java.util.Map<String, io.mosire.iibot.connect.core.ForwardCapability> known =
                new java.util.LinkedHashMap<>();
        known.put("bot-a", null);
        known.put("bot-b", null);
        List<AgentTool> tools = BotMcpTools.allForAccounts(
                board, notes, digestService,
                new BotReplyService(board, new FakeReplyPort(), CLOCK), "bot-a",
                known, null, null, null, null, null,
                new InMemoryConsumerConversationStore());

        AgentTool noteUpsert = tool(tools, "qq_user_note_upsert");
        AgentTool noteList = tool(tools, "qq_user_note_list");
        ToolResult missing = noteUpsert.execute(context(noteUpsert,
                Map.of("qqId", "qq-1", "note", "x")));
        assertThat(missing.success()).isFalse();
        assertThat(missing.code()).isEqualTo("ACCOUNT_REQUIRED");
        ToolResult listMissing = noteList.execute(context(noteList, Map.of()));
        assertThat(listMissing.code()).isEqualTo("ACCOUNT_REQUIRED");

        JsonNode written = call(noteUpsert, Map.of("accountId", "bot-b",
                "qqId", "qq-1", "note", "B 的备注"));
        assertThat(written.path("data").path("accountId").asText()).isEqualTo("bot-b");
        assertThat(notes.find("bot-b", "qq-1")).isPresent();
        assertThat(notes.find("bot-a", "qq-1")).isEmpty();
        // 直接 provider 工具在没有任何可用 provider 时不注册。
        assertThat(tools).extracting(AgentTool::name)
                .doesNotContain("qq_recent_messages", "qq_remote_history");
    }

    @Test
    void exposesCardAndFileMetadataWithoutInternalLocators() throws Exception {
        MessageSegment card = new MessageSegment(0, MessageSegment.Type.OTHER,
                "三国资料 | https://example.invalid/card", "", "", "", "", "", 0L,
                "", "json");
        MessageSegment file = new MessageSegment(1, MessageSegment.Type.FILE,
                "三国.json", "", "", "file-secret", "https://internal.invalid/raw",
                "application/octet-stream", 401_546L, "", "file");

        String cardJson = JSON.writeValueAsString(BotToolSupport.segmentMap(card));
        String fileJson = JSON.writeValueAsString(BotToolSupport.segmentMap(file));

        assertThat(cardJson)
                .contains("三国资料", "https://example.invalid/card", "\"rawType\":\"json\"")
                .doesNotContain("\"fileId\"", "\"url\"");
        assertThat(fileJson)
                .contains("三国.json", "\"rawType\":\"file\"", "\"byteSize\":401546",
                        "\"hasMedia\":true")
                .doesNotContain("file-secret", "https://internal.invalid/raw",
                        "\"fileId\"", "\"url\"");
    }

    private static ToolContext context(AgentTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args);
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
        private boolean mentionAll;
        private List<OutboundSegment> richAttachments = List.of();

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

        @Override
        public BotReplySendResult sendWithMentions(
                ChatSourceKey source, String text, String replyToMessageId,
                String mentionQqId, boolean mentionAll, String idempotencyKey) {
            this.mentionQqId = mentionQqId;
            this.mentionAll = mentionAll;
            return send(source, text, replyToMessageId, idempotencyKey);
        }

        @Override
        public BotReplySendResult sendRich(
                ChatSourceKey source, String text, String replyToMessageId,
                String mentionQqId, boolean mentionAll,
                List<OutboundSegment> attachments, String idempotencyKey) {
            this.mentionQqId = mentionQqId;
            this.mentionAll = mentionAll;
            this.richAttachments = attachments;
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
