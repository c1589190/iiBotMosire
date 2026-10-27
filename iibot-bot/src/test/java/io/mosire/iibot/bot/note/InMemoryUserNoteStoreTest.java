package io.mosire.iibot.bot.note;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryUserNoteStoreTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void noteIsSharedByAccountAndQqOnly() {
        InMemoryUserNoteStore store = new InMemoryUserNoteStore(CLOCK);
        UserNote first = store.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice",
                Set.of("vip"), "重点客户", 80), null);

        assertThat(first.revision()).isEqualTo(1L);
        assertThat(store.find("bot-1", "qq-1")).get()
                .extracting(UserNote::note, UserNote::priority)
                .containsExactly("重点客户", 80);
        assertThat(store.find("bot-2", "qq-1")).isEmpty();
    }

    @Test
    void updateRequiresMatchingRevisionAndAdvancesIt() {
        InMemoryUserNoteStore store = new InMemoryUserNoteStore(CLOCK);
        store.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice",
                Set.of("vip"), "第一次", 80), null);

        UserNote updated = store.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice",
                Set.of("vip", "friend"), "第二次", 20), 1L);

        assertThat(updated.revision()).isEqualTo(2L);
        assertThat(updated.tags()).containsExactlyInAnyOrder("vip", "friend");
        assertThatThrownBy(() -> store.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice",
                Set.of(), "", 0), 1L))
                .isInstanceOf(UserNoteRevisionConflictException.class)
                .hasMessageContaining("expected=1 actual=2");
    }

    @Test
    void listOrdersByPriorityThenUpdatedAt() {
        InMemoryUserNoteStore store = new InMemoryUserNoteStore(CLOCK);
        store.upsert(new UserNoteDraft("bot-1", "low", "Low", Set.of(), "", 1), null);
        store.upsert(new UserNoteDraft("bot-1", "high", "High", Set.of(), "", 90), null);

        assertThat(store.list(10)).extracting(UserNote::qqId)
                .containsExactly("high", "low");
    }
}
