package io.mosire.iibot.bot.inbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteInboxDigestStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsActiveItemAndCursorOnlyStates() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteInboxDigestStateStore store = new SqliteInboxDigestStateStore(db)) {
            store.save(new InboxDigestState("thread:1", "itm-1", 4L, 12L));
            store.save(InboxDigestState.cursorOnly("thread:2", 8L));

            assertThat(store.find("thread:1")).get()
                    .extracting(InboxDigestState::itemId, InboxDigestState::revision,
                            InboxDigestState::lastSeq)
                    .containsExactly("itm-1", 4L, 12L);
            assertThat(store.find("thread:2")).get()
                    .extracting(InboxDigestState::itemId, InboxDigestState::revision)
                    .containsExactly("", 0L);
        }

        try (SqliteInboxDigestStateStore reopened = new SqliteInboxDigestStateStore(db)) {
            assertThat(reopened.find("thread:1")).isPresent();
            reopened.clear("thread:1");
            assertThat(reopened.find("thread:1")).isEmpty();
        }
    }
}
