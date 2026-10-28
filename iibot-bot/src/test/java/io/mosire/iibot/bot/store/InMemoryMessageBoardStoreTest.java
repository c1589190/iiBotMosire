package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatThread;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMessageBoardStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @Test
    void appendsGroupMessagesFromDifferentSendersIntoOneThread() {
        InMemoryMessageBoardStore store = new InMemoryMessageBoardStore();

        MessageAppendResult first = store.append(inbound("m-1", "alice", "Alice", "第一句", T0));
        MessageAppendResult second = store.append(inbound("m-2", "bob", "Bob", "第二句",
                T0.plusSeconds(1)));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        assertThat(first.message().localSeq()).isEqualTo(1L);
        assertThat(second.message().localSeq()).isEqualTo(2L);
        assertThat(store.thread(GROUP.threadId())).get()
                .extracting(ChatThread::messageCount, ChatThread::lastSeq)
                .containsExactly(2L, 2L);
        assertThat(store.readThread(GROUP.threadId(), 0L, 10))
                .extracting(ChatMessage::senderId)
                .containsExactly("alice", "bob");
    }

    @Test
    void duplicateMessageKeyReturnsExistingRowWithoutAllocatingASecondSeq() {
        InMemoryMessageBoardStore store = new InMemoryMessageBoardStore();
        ChatMessage message = inbound("m-1", "alice", "Alice", "你好", T0);

        MessageAppendResult first = store.append(message);
        MessageAppendResult duplicate = store.append(message);
        MessageAppendResult next = store.append(
                inbound("m-2", "alice", "Alice", "下一句", T0.plusSeconds(1)));

        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.message().localSeq()).isEqualTo(first.message().localSeq());
        assertThat(next.message().localSeq()).isEqualTo(2L);
        assertThat(store.thread(GROUP.threadId())).get()
                .extracting(ChatThread::messageCount)
                .isEqualTo(2L);
    }

    @Test
    void differentSourcesNeverShareAThreadOrSequenceProjection() {
        InMemoryMessageBoardStore store = new InMemoryMessageBoardStore();
        ChatSourceKey privateSource = ChatSourceKey.privateChat("bot-1", "alice");

        store.append(inbound("m-1", "alice", "Alice", "群消息", T0));
        store.append(ChatMessage.inbound(privateSource, "m-2", "alice", "Alice",
                T0.plusSeconds(1), T0.plusSeconds(1), "私聊", "", ""));

        assertThat(store.readThread(GROUP.threadId(), 0L, 10)).hasSize(1);
        assertThat(store.readThread(privateSource.threadId(), 0L, 10)).hasSize(1);
        assertThat(store.threads(10)).extracting(ChatThread::threadId)
                .containsExactlyInAnyOrder(GROUP.threadId(), privateSource.threadId());
    }

    @Test
    void readThreadHonoursAfterSeqAndLimit() {
        InMemoryMessageBoardStore store = new InMemoryMessageBoardStore();
        for (int i = 1; i <= 4; i++) {
            store.append(inbound("m-" + i, "alice", "Alice", "msg-" + i,
                    T0.plusSeconds(i)));
        }

        assertThat(store.readThread(GROUP.threadId(), 0L, 2))
                .extracting(ChatMessage::localSeq)
                .containsExactly(1L, 2L);
        assertThat(store.readThread(GROUP.threadId(), 1L, 10))
                .extracting(ChatMessage::localSeq)
                .containsExactly(2L, 3L, 4L);
    }

    @Test
    void lastInboundSeqIgnoresOwnOutboundMessages() {
        InMemoryMessageBoardStore store = new InMemoryMessageBoardStore();
        store.append(inbound("m-1", "alice", "Alice", "收到", T0));
        store.append(ChatMessage.outbound(GROUP, "reply-1", T0, "回复", "", ""));
        store.append(inbound("m-2", "alice", "Alice", "再说", T0.plusSeconds(1)));

        assertThat(store.lastInboundSeqAfter(GROUP.threadId(), 0L)).isEqualTo(3L);
        assertThat(store.lastInboundSeqAfter(GROUP.threadId(), 2L)).isEqualTo(3L);
        assertThat(store.lastInboundSeqAfter(GROUP.threadId(), 3L)).isEqualTo(3L);
    }

    @Test
    void displayNameCanBeUpdatedWithoutChangingThreadIdentityOrCounts() {
        InMemoryMessageBoardStore store = new InMemoryMessageBoardStore();
        store.append(inbound("m-1", "alice", "Alice", "你好", T0));

        assertThat(store.updateThreadDisplayName(GROUP.threadId(), "测试群")).isTrue();
        assertThat(store.updateThreadDisplayName(GROUP.threadId(), "测试群")).isFalse();
        assertThat(store.thread(GROUP.threadId())).get()
                .extracting(ChatThread::threadId, ChatThread::displayName,
                        ChatThread::messageCount)
                .containsExactly(GROUP.threadId(), "测试群", 1L);
        assertThat(store.updateThreadDisplayName("thread:missing", "不存在")).isFalse();
    }

    private static ChatMessage inbound(String messageId, String senderId, String senderName,
                                       String text, Instant receivedAt) {
        return ChatMessage.inbound(GROUP, messageId, senderId, senderName, receivedAt,
                receivedAt, text, "", "");
    }
}
