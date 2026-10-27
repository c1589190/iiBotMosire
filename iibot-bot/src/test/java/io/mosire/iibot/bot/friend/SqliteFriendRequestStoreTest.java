package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteFriendRequestStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void persistsFlagLocallyButHidesItFromRecord() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteFriendRequestStore store = new SqliteFriendRequestStore(db)) {
            StoredFriendRequest stored = store.upsert(new FriendRequestEvent(
                    "raw-flag", "qq-2", "Bob", "hello", T0));

            assertThat(stored.flag()).isEqualTo("raw-flag");
            assertThat(stored.request().requestId()).doesNotContain("raw-flag");
            assertThat(store.listPending(10)).singleElement()
                    .extracting(FriendRequestRecord::requestId)
                    .isEqualTo(stored.request().requestId());
            store.markDecided(stored.request().requestId(), "APPROVED", "备注", T0);
            assertThat(store.listPending(10)).isEmpty();
        }
        try (SqliteFriendRequestStore reopened = new SqliteFriendRequestStore(db)) {
            assertThat(reopened.find("missing")).isEmpty();
        }
    }
}
