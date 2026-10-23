package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecentMessageDeduplicatorTest {

    @Test
    void suppressesDuplicatesOnlyWithinTtl() {
        RecentMessageDeduplicator deduplicator =
                new RecentMessageDeduplicator(Duration.ofMillis(100), 100);

        assertThat(deduplicator.markIfNew("self:1:msg:2", 0L)).isTrue();
        assertThat(deduplicator.markIfNew("self:1:msg:2", 50_000_000L)).isFalse();
        assertThat(deduplicator.markIfNew("self:1:msg:2", 100_000_000L)).isTrue();
    }

    @Test
    void evictsOldestEntryWhenCapacityExceeded() {
        RecentMessageDeduplicator deduplicator =
                new RecentMessageDeduplicator(Duration.ofMinutes(1), 2);

        assertThat(deduplicator.markIfNew("a", 1L)).isTrue();
        assertThat(deduplicator.markIfNew("b", 2L)).isTrue();
        assertThat(deduplicator.markIfNew("c", 3L)).isTrue();

        assertThat(deduplicator.markIfNew("a", 4L)).isTrue();
        assertThat(deduplicator.markIfNew("b", 5L)).isTrue();
    }
}
