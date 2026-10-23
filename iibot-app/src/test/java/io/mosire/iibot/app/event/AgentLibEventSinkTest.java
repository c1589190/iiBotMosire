package io.mosire.iibot.app.event;

import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLibEventSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAcrossRestartAndHonoursExclusiveCursorAndLimit() {
        Path db = tempDir.resolve("events.db");
        String secret = "sk-test-do-not-leak";

        long first;
        long second;
        try (AgentLibEventSink sink = new AgentLibEventSink(db, List.of(secret))) {
            first = sink.append(event(1, "first", Map.of("note", "hello")));
            second = sink.append(event(2, "second", Map.of("apiKey", secret,
                    "summary", "contains " + secret + " and more")));
            sink.append(event(3, "third", Map.of("note", "bye")));
            assertThat(first).isPositive();
            assertThat(second).isGreaterThan(first);
        }

        try (SqliteEventReadPort port = new SqliteEventReadPort(db)) {
            List<TaskflowEvent> firstTwo = port.readAfter(0, 2);
            assertThat(firstTwo).extracting(TaskflowEvent::idempotencyKey)
                    .containsExactly("first", "second");
            assertThat(firstTwo.get(0).seq()).isEqualTo(first);
            assertThat(firstTwo.get(1).seq()).isEqualTo(second);

            List<TaskflowEvent> rest = port.readAfter(firstTwo.get(1).seq(), 10);
            assertThat(rest).extracting(TaskflowEvent::idempotencyKey).containsExactly("third");
            assertThat(rest.get(0).seq()).isGreaterThan(second);

            TaskflowEvent secondEvent = firstTwo.get(1);
            assertThat(secondEvent.payload()).containsEntry("apiKey", "[REDACTED]");
            assertThat(secondEvent.payload().get("summary")).doesNotContain(secret)
                    .contains("[REDACTED]");
            assertThat(secondEvent.actorRole()).isEqualTo(ActorRole.PLAYER);
            assertThat(secondEvent.turnId()).isEqualTo("turn-1");
        }
    }

    @Test
    void lastStoredSeqReadsMaxSeqWithoutConsumingCursorAndRejectsAfterClose() {
        Path db = tempDir.resolve("last-seq.db");
        try (AgentLibEventSink sink = new AgentLibEventSink(db)) {
            sink.append(event(1, "first", Map.of()));
            sink.append(event(2, "second", Map.of()));
        }

        SqliteEventReadPort port = new SqliteEventReadPort(db);
        assertThat(port.lastStoredSeq()).isEqualTo(2L);
        assertThat(port.readAfter(0, 1)).extracting(TaskflowEvent::idempotencyKey)
                .containsExactly("first");
        // readAfter 不消费游标；lastStoredSeq 也不是游标，重复读取稳定。
        assertThat(port.lastStoredSeq()).isEqualTo(2L);
        assertThat(port.readAfter(0, 10)).hasSize(2);
        port.close();
        org.assertj.core.api.Assertions.assertThatThrownBy(port::lastStoredSeq)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lastStoredSeqIsZeroForAnEmptyEventDatabase() {
        Path db = tempDir.resolve("empty.db");
        try (AgentLibEventSink ignored = new AgentLibEventSink(db);
             SqliteEventReadPort port = new SqliteEventReadPort(db)) {
            assertThat(port.lastStoredSeq()).isZero();
        }
    }

    @Test
    void appendAfterCloseIsRejectedAndCloseIsIdempotent() {
        Path db = tempDir.resolve("closed.db");
        AgentLibEventSink sink = new AgentLibEventSink(db);
        sink.close();
        sink.close();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sink.append(event(1, "late", Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TaskflowEvent event(long domainSeq, String key, Map<String, String> payload) {
        return new TaskflowEvent(domainSeq, Instant.parse("2026-10-24T00:00:0" + domainSeq + "Z"),
                EventType.TURN_OPENED, ActorRole.PLAYER, "player-1", "turn-1", key, payload);
    }
}
