package io.mosire.iibot.bot.weight;

import io.mosire.iibot.bot.chat.ChatType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteSourceWeightStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void persistsPolicyAndDecisionAndEnforcesRevision() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteSourceWeightStore store = new SqliteSourceWeightStore(db)) {
            SourceWeightPolicy policy = new SourceWeightPolicy(
                    "thread:group:bot-1:group-9", ChatType.GROUP, "group-9",
                    10, 100, Map.of(WeightSignal.MENTION, 200),
                    Map.of("上线", 120), 20, 180000L, 60000L,
                    true, 0L, T0);
            SourceWeightPolicy saved = store.savePolicy(policy, null);
            assertThat(saved.revision()).isEqualTo(1L);
            assertThat(store.findPolicy(policy.threadId())).contains(saved);

            SourceWeightPolicy changed = new SourceWeightPolicy(
                    saved.threadId(), saved.chatType(), saved.targetId(), 500, 100,
                    saved.signalWeights(), saved.keywordWeights(), 20,
                    180000L, 60000L, true, 0L, T0.plusSeconds(1));
            assertThat(store.savePolicy(changed, 1L).revision()).isEqualTo(2L);
            assertThatThrownBy(() -> store.savePolicy(changed, 1L))
                    .isInstanceOf(SourceWeightConflictException.class);

            MessageWeightDecision decision = new MessageWeightDecision(
                    "in:thread:group:bot-1:group-9:m-1", policy.threadId(),
                    500, 200, 0, 700, WeightWakeReason.MENTION,
                    List.of(WeightSignal.MENTION), List.of(), true, false, 2L, T0);
            store.saveDecision(decision);
            assertThat(store.findDecision(decision.messageKey())).contains(decision);
            assertThat(store.decisions(List.of(decision.messageKey())))
                    .containsEntry(decision.messageKey(), decision);
        }
        try (SqliteSourceWeightStore reopened = new SqliteSourceWeightStore(db)) {
            assertThat(reopened.findPolicy("thread:group:bot-1:group-9")).isPresent();
        }
    }
}
