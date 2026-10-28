package io.mosire.iibot.bot.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerConversationStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    private static final String CONSUMER = ConsumerConversationStore.DEFAULT_CONSUMER_ID;
    private static final String THREAD = "thread:group:bot:group-9";

    @TempDir
    Path tempDir;

    @Test
    void inMemoryCursorsOnlyAdvanceAndKeepConsumersIsolated() {
        InMemoryConsumerConversationStore store = new InMemoryConsumerConversationStore();

        ConsumerConversationState first = store.recordDelivered(
                CONSUMER, THREAD, 5L, T0);
        ConsumerConversationState noRegression = store.recordDelivered(
                CONSUMER, THREAD, 3L, T0.plusSeconds(1));
        ConsumerConversationState acked = store.advanceRead(
                CONSUMER, THREAD, 4L, T0.plusSeconds(2));
        ConsumerConversationState ackNoRegression = store.advanceRead(
                CONSUMER, THREAD, 2L, T0.plusSeconds(3));
        ConsumerConversationState deliveredAhead = store.recordDelivered(
                CONSUMER, THREAD, 8L, T0.plusSeconds(4));

        assertThat(first).extracting(ConsumerConversationState::readSeq,
                        ConsumerConversationState::deliveredSeq)
                .containsExactly(0L, 5L);
        assertThat(noRegression.deliveredSeq()).isEqualTo(5L);
        assertThat(acked).extracting(ConsumerConversationState::readSeq,
                        ConsumerConversationState::deliveredSeq)
                .containsExactly(4L, 5L);
        assertThat(ackNoRegression.readSeq()).isEqualTo(4L);
        assertThat(deliveredAhead.deliveredSeq()).isEqualTo(8L);
        assertThat(store.find("another-consumer", THREAD)).isEmpty();
        assertThatThrownBy(() -> store.advanceRead(
                CONSUMER, THREAD, 9L, T0.plusSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliveredSeq");
    }

    @Test
    void sqliteCursorsPersistAcrossReopen() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteConsumerConversationStore store =
                     new SqliteConsumerConversationStore(db)) {
            store.recordDelivered(CONSUMER, THREAD, 5L, T0);
            store.advanceRead(CONSUMER, THREAD, 4L, T0.plusSeconds(1));
            store.recordDelivered(CONSUMER, THREAD, 3L, T0.plusSeconds(2));
            store.advanceRead(CONSUMER, THREAD, 2L, T0.plusSeconds(3));
        }

        try (SqliteConsumerConversationStore reopened =
                     new SqliteConsumerConversationStore(db)) {
            assertThat(reopened.find(CONSUMER, THREAD)).get()
                    .extracting(ConsumerConversationState::readSeq,
                            ConsumerConversationState::deliveredSeq)
                    .containsExactly(4L, 5L);

            ConsumerConversationState delivered = reopened.recordDelivered(
                    CONSUMER, THREAD, 9L, T0.plusSeconds(4));
            assertThat(delivered.deliveredSeq()).isEqualTo(9L);
            assertThatThrownBy(() -> reopened.advanceRead(
                    CONSUMER, THREAD, 10L, T0.plusSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deliveredSeq");
        }
    }
}
