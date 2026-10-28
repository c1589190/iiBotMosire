package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.inbox.InboxDigestAudit;
import io.mosire.iibot.bot.inbox.InboxDigestState;
import io.mosire.iibot.bot.inbox.SqliteInboxDigestAuditStore;
import io.mosire.iibot.bot.inbox.SqliteInboxDigestStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
    void sqliteBackfillsConsumerCursorFromLegacyDigestState() {
        Path db = tempDir.resolve("legacy-bot.db");
        String threadId = "thread:group:Ym90LTE:Z3JvdXAtOQ";
        try (SqliteInboxDigestStateStore digest =
                     new SqliteInboxDigestStateStore(db)) {
            digest.save(new InboxDigestState(threadId, "item-legacy", 11L, 42L));
        }

        try (SqliteConsumerConversationStore store =
                     new SqliteConsumerConversationStore(db)) {
            assertThat(store.find(CONSUMER, threadId)).get()
                    .extracting(ConsumerConversationState::readSeq,
                            ConsumerConversationState::deliveredSeq)
                    .containsExactly(42L, 42L);
        }
    }

    @Test
    void sqliteReconcileUsesActiveItemFromSeqAndRepairsExistingRows() throws Exception {
        Path db = tempDir.resolve("reconcile-bot.db");
        String newerThread = "thread:group:Ym90LTE:Z3JvdXAtMQ";
        String shortThread = "thread:group:Ym90LTE:Z3JvdXAtMg";
        try (SqliteInboxDigestStateStore digest = new SqliteInboxDigestStateStore(db);
             SqliteInboxDigestAuditStore audit = new SqliteInboxDigestAuditStore(db)) {
            digest.save(new InboxDigestState(newerThread, "item-newer", 5L, 2018L));
            audit.upsert(new InboxDigestAudit(newerThread, "item-newer",
                    2000L, 2018L, "新未读", 0, "available", 5L, T0, T0, null));
            digest.save(new InboxDigestState(shortThread, "item-short", 3L, 2048L));
            audit.upsert(new InboxDigestAudit(shortThread, "item-short",
                    2047L, 2048L, "短未读", 0, "available", 3L, T0, T0, null));
        }

        try (SqliteConsumerConversationStore store = new SqliteConsumerConversationStore(db)) {
            assertThat(store.find(CONSUMER, newerThread)).get()
                    .extracting(ConsumerConversationState::readSeq,
                            ConsumerConversationState::deliveredSeq)
                    .containsExactly(1999L, 1999L);
            assertThat(store.find(CONSUMER, shortThread)).get()
                    .extracting(ConsumerConversationState::readSeq,
                            ConsumerConversationState::deliveredSeq)
                    .containsExactly(2046L, 2046L);
        }

        // 模拟 P8 旧回填/手工种子留下的错误行，并让 v2 迁移再次执行。
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE consumer_thread_state SET read_seq = 1244, delivered_seq = 1244
                    WHERE consumer_id = 'default' AND thread_id = '""" + newerThread + "'");
            statement.executeUpdate("""
                    UPDATE consumer_thread_state SET read_seq = 2048, delivered_seq = 2048
                    WHERE consumer_id = 'default' AND thread_id = '""" + shortThread + "'");
            statement.executeUpdate("DELETE FROM consumer_state_migration WHERE version = 2");
        }

        try (SqliteConsumerConversationStore repaired = new SqliteConsumerConversationStore(db)) {
            assertThat(repaired.find(CONSUMER, newerThread)).get()
                    .extracting(ConsumerConversationState::readSeq,
                            ConsumerConversationState::deliveredSeq)
                    .containsExactly(1999L, 1999L);
            assertThat(repaired.find(CONSUMER, shortThread)).get()
                    .extracting(ConsumerConversationState::readSeq,
                            ConsumerConversationState::deliveredSeq)
                    .containsExactly(2046L, 2046L);
        }
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
