package io.mosire.iibot.bot.inbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteInboxDigestAuditStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void persistsAvailableAndDoneAudit() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteInboxDigestAuditStore store = new SqliteInboxDigestAuditStore(db)) {
            store.upsert(new InboxDigestAudit("thread:1", "itm-1", 1, 2,
                    "摘要", 0, "available", 1, T0, T0, null));
            store.markDone("thread:1", "itm-1", T0.plusSeconds(1));
            assertThat(store.list("thread:1", 10)).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo("done");
                assertThat(item.ackedAt()).isEqualTo(T0.plusSeconds(1));
                assertThat(item.summary()).isEqualTo("摘要");
            });
        }
    }
}
