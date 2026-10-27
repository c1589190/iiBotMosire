package io.mosire.iibot.bot.note;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteUserNoteStoreTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void persistsNoteAndTagsAcrossReopenAndChecksRevision() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteUserNoteStore store = new SqliteUserNoteStore(db, CLOCK)) {
            UserNote first = store.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice",
                    Set.of("vip", "friend"), "重点联系人", 80), null);
            assertThat(first.revision()).isEqualTo(1L);
            assertThat(first.tags()).containsExactlyInAnyOrder("vip", "friend");
        }

        try (SqliteUserNoteStore reopened = new SqliteUserNoteStore(db, CLOCK)) {
            UserNote loaded = reopened.find("bot-1", "qq-1").orElseThrow();
            assertThat(loaded.note()).isEqualTo("重点联系人");
            assertThat(loaded.tags()).containsExactlyInAnyOrder("vip", "friend");
            UserNote second = reopened.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice",
                    Set.of("vip"), "更新", 10), 1L);
            assertThat(second.revision()).isEqualTo(2L);
            assertThatThrownBy(() -> reopened.upsert(new UserNoteDraft("bot-1", "qq-1",
                    "Alice", Set.of(), "", 0), 1L))
                    .isInstanceOf(UserNoteRevisionConflictException.class);
        }
    }
}
