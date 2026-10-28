package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.connect.core.MessageSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteMessageBoardStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @TempDir
    Path tempDir;

    @Test
    void persistsDeduplicatesAndProjectsOneGroupThread() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(db)) {
            MessageAppendResult first = store.append(inbound("m-1", "alice", "Alice", "你好"));
            MessageAppendResult duplicate = store.append(inbound("m-1", "alice", "Alice", "你好"));
            MessageAppendResult second = store.append(inbound("m-2", "bob", "Bob", "收到"));

            assertThat(first.created()).isTrue();
            assertThat(first.message().localSeq()).isEqualTo(1L);
            assertThat(duplicate.created()).isFalse();
            assertThat(second.message().localSeq()).isEqualTo(2L);
            assertThat(store.readThread(GROUP.threadId(), 0L, 10))
                    .extracting(ChatMessage::senderId)
                    .containsExactly("alice", "bob");
            assertThat(store.thread(GROUP.threadId())).get()
                    .extracting(ChatThread::messageCount, ChatThread::lastSeq)
                    .containsExactly(2L, 2L);
        }

        assertThat(Files.isRegularFile(db)).isTrue();
        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(db)) {
            assertThat(reopened.find(ChatMessage.inboundKey(GROUP, "m-1"))).isPresent();
            assertThat(reopened.threads(10)).hasSize(1);
        }
    }

    @Test
    void persistsOutboundDeliveryStateAcrossReopen() {
        Path db = tempDir.resolve("bot.db");
        ChatMessage outbound = ChatMessage.outbound(GROUP, "reply-1", T0,
                "收到", "", "");
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(db)) {
            MessageAppendResult appended = store.append(outbound);
            ChatMessage sent = store.updateDelivery(appended.message().messageKey(),
                    DeliveryState.SENT, "qq-100", T0.plusSeconds(1));

            assertThat(sent.direction()).isEqualTo(ChatDirection.OUT);
            assertThat(sent.deliveryState()).isEqualTo(DeliveryState.SENT);
            assertThat(sent.messageId()).isEqualTo("qq-100");
            assertThat(sent.sentAt()).isEqualTo(T0.plusSeconds(1));
        }

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(db)) {
            assertThat(reopened.find(outbound.messageKey())).get()
                    .extracting(ChatMessage::direction, ChatMessage::deliveryState,
                            ChatMessage::messageId)
                    .containsExactly(ChatDirection.OUT, DeliveryState.SENT, "qq-100");
        }
    }

    @Test
    void countsOnlyInboundMessagesAfterCursor() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(db)) {
            store.append(inbound("m-1", "alice", "Alice", "第一句"));
            store.append(ChatMessage.outbound(GROUP, "reply-1", T0, "收到", "", ""));
            store.append(inbound("m-3", "alice", "Alice", "第三句"));
            store.append(inbound("m-4", "bob", "Bob", "第四句"));

            assertThat(store.countInboundAfter(GROUP.threadId(), 0L)).isEqualTo(3L);
            assertThat(store.countInboundAfter(GROUP.threadId(), 1L)).isEqualTo(2L);
            assertThat(store.countInboundAfter(GROUP.threadId(), 2L)).isEqualTo(2L);
            assertThat(store.countInboundAfter(GROUP.threadId(), 3L)).isEqualTo(1L);
            assertThat(store.countInboundAfter(GROUP.threadId(), 4L)).isZero();
        }
    }

    @Test
    void persistsSegmentsAndSupportsHistoryPaging() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(db)) {
            ChatMessage first = inbound("m-1", "alice", "Alice", "第一句");
            store.append(first, java.util.List.of(
                    MessageSegment.text(0, "第一句"),
                    MessageSegment.image(1, "file-1", "https://example.invalid/x.png",
                            "image/png", 3L)));
            store.append(inbound("m-2", "alice", "Alice", "第二句"));
            store.append(inbound("m-3", "alice", "Alice", "第三句"));

            assertThat(store.segments(first.messageKey()))
                    .extracting(MessageSegment::type)
                    .containsExactly(MessageSegment.Type.TEXT, MessageSegment.Type.IMAGE);
            assertThat(store.findByMessageId(GROUP.threadId(), "m-1")).isPresent();
            assertThat(store.readThreadBefore(GROUP.threadId(), 4L, 2))
                    .extracting(ChatMessage::localSeq)
                    .containsExactly(2L, 3L);
        }
    }

    private static ChatMessage inbound(String id, String senderId, String senderName, String text) {
        return ChatMessage.inbound(GROUP, id, senderId, senderName, T0, T0, text, "", "");
    }
}
