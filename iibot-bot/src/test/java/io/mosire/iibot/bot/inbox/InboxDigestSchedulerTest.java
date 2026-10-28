package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.note.InMemoryUserNoteStore;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InboxDigestSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @Test
    void debouncesOneThreadAndPublishesAllMessagesInOneDigest() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        RecordingPort port = new RecordingPort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "第一条", "", ""));
        board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                "第二条", "", ""));

        try (InboxDigestScheduler scheduler = new InboxDigestScheduler(
                service, Duration.ofMillis(40))) {
            scheduler.enqueue(GROUP.threadId());
            Thread.sleep(10);
            scheduler.enqueue(GROUP.threadId());

            assertThat(port.published.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.lastError()).isNull();
            assertThat(scheduler.pendingCount()).isZero();
        }

        assertThat(port.digests).hasSize(1);
        assertThat(port.digests.getFirst().messages()).hasSize(2);
    }

    @Test
    void zeroDelayUsesConfiguredDebounceWindow() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        RecordingPort port = new RecordingPort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "窗口内", "", ""));

        try (InboxDigestScheduler scheduler = new InboxDigestScheduler(
                service, Duration.ofMillis(120))) {
            scheduler.enqueue(GROUP.threadId(), 0L);
            Thread.sleep(30);
            assertThat(port.digests).isEmpty();
            assertThat(port.published.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(port.digests).hasSize(1);
    }

    @Test
    void flushNowInvalidatesPendingTimer() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        RecordingPort port = new RecordingPort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                "立即", "", ""));

        try (InboxDigestScheduler scheduler = new InboxDigestScheduler(
                service, Duration.ofSeconds(5))) {
            scheduler.enqueue(GROUP.threadId());
            scheduler.flushNow(GROUP.threadId());

            assertThat(port.digests).hasSize(1);
            assertThat(scheduler.pendingCount()).isZero();
        }
    }

    private static final class RecordingPort implements InboxDigestPort {
        private final List<InboxDigest> digests = new ArrayList<>();
        private final CountDownLatch published = new CountDownLatch(1);

        @Override
        public InboxDigestItem push(String dedupeKey, InboxDigest digest) {
            digests.add(digest);
            published.countDown();
            return new InboxDigestItem("itm-" + digests.size(), 1L, "available");
        }

        @Override
        public Optional<InboxDigestItem> view(String itemId) {
            return Optional.empty();
        }

        @Override
        public InboxDigestItem update(String itemId, long expectedRevision,
                                      InboxDigest digest) {
            digests.add(digest);
            return new InboxDigestItem(itemId, expectedRevision + 1L, "available");
        }

        @Override
        public void archive(String itemId, long expectedRevision) {
        }
    }
}
