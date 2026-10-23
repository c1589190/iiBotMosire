package io.mosire.iibot.app.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskflowSnapshotStoreTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void roundTripsCompleteEngineSnapshotIncludingTimesAndAllDomainCollections() throws Exception {
        TaskflowStateSnapshot snapshot = completeSnapshot();
        TaskflowSnapshotStore store = new TaskflowSnapshotStore(tempDir);
        assertThat(store.path()).isEqualTo(tempDir.resolve("taskflow-state.json"));

        store.save(snapshot);

        assertThat(store.load()).contains(snapshot);
        assertThat(store.load().orElseThrow().turns()).hasSize(2);
        assertThat(store.load().orElseThrow().infoRequests()).isNotEmpty();
        assertThat(store.load().orElseThrow().briefsByRequest()).isNotEmpty();
        assertThat(store.load().orElseThrow().settingProposals()).isNotEmpty();
        assertThat(store.load().orElseThrow().idempotencyResults()).isNotEmpty();
        assertThat(Files.readString(store.path()))
                .contains("2026-10-24T00:00:00Z")
                .doesNotContain("1776988800");
    }

    @Test
    void missingFileReturnsEmptyAndExplicitPathIsHonoured() {
        Path explicit = tempDir.resolve("nested").resolve("custom-state.json");
        TaskflowSnapshotStore store = TaskflowSnapshotStore.atFile(explicit);

        assertThat(store.load()).isEmpty();
        assertThat(store.path()).isEqualTo(explicit.toAbsolutePath().normalize());
    }

    @Test
    void corruptJsonUnknownFieldsAndTrailingTokensFailClosedWithoutEchoingBody() throws Exception {
        TaskflowSnapshotStore store = new TaskflowSnapshotStore(tempDir);
        String privateMarker = "TOP-SECRET-SNAPSHOT-BODY";
        Files.writeString(store.path(), "{\"schemaVersion\":1,\"secret\":\"" + privateMarker
                + "\", trailing");

        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASKFLOW_SNAPSHOT_CORRUPT")
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(privateMarker));

        store.save(completeSnapshot());
        JsonNode valid = JSON.readTree(store.path().toFile());
        ((ObjectNode) valid).put("unknownField", "must-fail");
        JSON.writeValue(store.path().toFile(), valid);
        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASKFLOW_SNAPSHOT_CORRUPT");

        store.save(completeSnapshot());
        Files.writeString(store.path(), Files.readString(store.path()) + " {}");
        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASKFLOW_SNAPSHOT_CORRUPT");

        store.save(completeSnapshot());
        ObjectNode futureSchema = (ObjectNode) JSON.readTree(store.path().toFile());
        futureSchema.put("schemaVersion", 999);
        JSON.writeValue(store.path().toFile(), futureSchema);
        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASKFLOW_SNAPSHOT_SCHEMA");
    }

    @Test
    void atomicReplacementNeverExposesHalfWrittenJsonAndLeavesNoTempFiles() throws Exception {
        TaskflowSnapshotStore store = new TaskflowSnapshotStore(tempDir);
        TaskflowStateSnapshot first = completeSnapshot();
        store.save(first);
        String before = Files.readString(store.path());

        for (int i = 1; i <= 10; i++) {
            TaskflowStateSnapshot replacement = withWorldVersion(first, first.worldVersion() + i);
            store.save(replacement);
            JsonNode parsed = JSON.readTree(store.path().toFile());
            assertThat(parsed.path("worldVersion").asLong())
                    .isEqualTo(first.worldVersion() + i);
            assertThat(Files.readString(store.path())).isNotBlank();
        }

        assertThat(before).isNotBlank();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    private static TaskflowStateSnapshot withWorldVersion(TaskflowStateSnapshot source,
                                                          long worldVersion) {
        return new TaskflowStateSnapshot(source.schemaVersion(), source.promptVersion(),
                source.engineRevision(), worldVersion, source.eventCount(),
                source.lastEventSeq(), source.lastTurnNo(), source.currentTurnId(),
                source.parties(), source.worldFacts(), source.infoRequests(),
                source.briefsByRequest(), source.settingProposals(), source.turns(),
                source.idempotencyResults());
    }

    private static TaskflowStateSnapshot completeSnapshot() {
        InMemoryEventStore events = new InMemoryEventStore();
        TaskflowEngine engine = new TaskflowEngine(CLOCK, events, "prompt-v1",
                WorldDelta.DEFAULT_ALLOWED_TYPES, 64, 200_000, 8_192);
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        Actor p1 = new Actor("player-1", ActorRole.PLAYER);
        Actor p2 = new Actor("player-2", ActorRole.PLAYER);

        expectOk(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "reg-director"));
        expectOk(engine.registerPlayer(director,
                new PlayerProfile("player-1", "qq-p1", "Player One"),
                engine.status().engineRevision(), "reg-p1"));
        expectOk(engine.registerPlayer(director,
                new PlayerProfile("player-2", "qq-p2", "Player Two"),
                engine.status().engineRevision(), "reg-p2"));
        expectOk(engine.loadWorldFacts(director, 0, List.of(
                        new WorldFact("public-1", 1, "港口钟声", Visibility.PUBLIC),
                        new WorldFact("p1-secret", 1, "给玩家一的密信",
                                Visibility.forPlayers(java.util.Set.of("player-1"))),
                        new WorldFact("director-secret", 1, "仅推演可见",
                                Visibility.DIRECTOR_ONLY)),
                engine.status().engineRevision(), "load-facts"));

        String settingId = expectOk(engine.proposeSetting(p1, "旧灯塔在风暴中熄灭",
                Visibility.PUBLIC, engine.status().engineRevision(), "propose-setting"))
                .referenceId().orElseThrow();
        expectOk(engine.decideSetting(director, settingId, true, "采纳",
                engine.status().engineRevision(), "approve-setting"));

        expectOk(engine.openTurn(director, engine.status().engineRevision(), "open-turn",
                CLOCK.instant().plus(Duration.ofHours(1))));
        String requestId = expectOk(engine.requestBrief(p1, "港口安全吗？",
                engine.turnView().orElseThrow().revision(), "ask-brief"))
                .referenceId().orElseThrow();
        expectOk(engine.issueBrief(director, requestId, "港口目前安全。",
                List.of("public-1"), CLOCK.instant().plus(Duration.ofHours(2)),
                engine.turnView().orElseThrow().revision(), "answer-brief"));

        DecisionDraft draft1 = expectOk(engine.createDecisionDraft(p1,
                new DecisionDraftInput("调查港口", List.of("询问船工"),
                        Map.of("people", "3"), "北码头", "遇险撤回",
                        CLOCK.instant().plus(Duration.ofHours(2))),
                engine.turnView().orElseThrow().revision(), "draft-p1")).referenceId()
                .map(id -> engine.draft(id).orElseThrow()).orElseThrow();
        expectOk(engine.confirmDecision(p1, draft1.draftId(), draft1.previewHash(),
                engine.turnView().orElseThrow().revision(), "confirm-p1"));
        DecisionDraft draft2 = expectOk(engine.createDecisionDraft(p2,
                new DecisionDraftInput("巡逻码头", List.of("沿河巡逻"),
                        Map.of("people", "2"), "南码头", "保持联络",
                        CLOCK.instant().plus(Duration.ofHours(2))),
                engine.turnView().orElseThrow().revision(), "draft-p2")).referenceId()
                .map(id -> engine.draft(id).orElseThrow()).orElseThrow();
        expectOk(engine.confirmDecision(p2, draft2.draftId(), draft2.previewHash(),
                engine.turnView().orElseThrow().revision(), "confirm-p2"));

        expectOk(engine.lockTurn(director, engine.turnView().orElseThrow().revision(),
                "lock-turn"));
        expectOk(engine.startAdjudication(director,
                engine.turnView().orElseThrow().revision(), "start-adjudication"));
        String firstTurnId = engine.turnView().orElseThrow().turnId();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION, firstTurnId,
                engine.status().worldVersion(), "港口第一回合结束。",
                Map.of("player-1", "发现线索", "player-2", "完成巡逻"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "investigated")), List.of());
        expectOk(engine.commitAdjudication(director,
                engine.turnView().orElseThrow().revision(), "commit-adjudication", proposal));
        expectOk(engine.publishTurn(director, engine.turnView().orElseThrow().revision(),
                "publish-turn"));

        expectOk(engine.openTurn(director, engine.status().engineRevision(), "open-second",
                CLOCK.instant().plus(Duration.ofHours(2))));
        expectOk(engine.createDecisionDraft(p1,
                new DecisionDraftInput("等待第二回合", List.of("观察"),
                        Map.of("people", "1"), "灯塔", "撤退",
                        CLOCK.instant().plus(Duration.ofHours(3))),
                engine.turnView().orElseThrow().revision(), "draft-second"));
        expectOk(engine.proposeSetting(p1, "第二回合待审设定", Visibility.PUBLIC,
                engine.status().engineRevision(), "propose-second"));
        return engine.exportState();
    }

    private static CommandResult expectOk(CommandResult result) {
        assertThat(result).isNotNull();
        assertThat(result.ok()).as("command %s: %s", result.code(), result.message()).isTrue();
        return result;
    }
}
