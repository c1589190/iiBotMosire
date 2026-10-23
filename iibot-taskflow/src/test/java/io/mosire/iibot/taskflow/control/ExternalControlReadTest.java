package io.mosire.iibot.taskflow.control;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 事件游标、只读回合视图与不变量检查。 */
class ExternalControlReadTest {

    @Test
    void eventPollUsesExclusiveCursorLimitAndSortedSeq() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "P1", "expectedRevision", 0L,
                        "idempotencyKey", "ev-reg-p1", "reason", "测试")).code()).isNull();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_REGISTER_DIRECTOR,
                ControlTestFixture.args("qqId", "qq-d1", "playerId", "d1",
                        "displayName", "D1", "expectedRevision", 1L,
                        "idempotencyKey", "ev-reg-d1", "reason", "测试")).code()).isNull();

        ToolResult first = fixture.call(control, ExternalControlToolSet.TF_EVENT_POLL,
                Map.of("afterSeq", 0, "limit", 10));
        assertThat(first.code()).isNull();
        JsonNode firstJson = fixture.json(first);
        assertThat(firstJson.path("data").path("events")).hasSize(2);
        assertThat(firstJson.path("data").path("events").get(0).path("seq").asLong()).isEqualTo(1);
        assertThat(firstJson.path("data").path("events").get(1).path("seq").asLong()).isEqualTo(2);
        assertThat(firstJson.path("data").path("nextCursor").asLong()).isEqualTo(2);

        ToolResult second = fixture.call(control, ExternalControlToolSet.TF_EVENT_POLL,
                Map.of("afterSeq", 2, "limit", 10));
        assertThat(fixture.json(second).path("data").path("events")).isEmpty();
        assertThat(fixture.json(second).path("data").path("nextCursor").asLong()).isEqualTo(2);

        // limit 严格生效，且读取不消费游标。
        ToolResult limited = fixture.call(control, ExternalControlToolSet.TF_EVENT_POLL,
                Map.of("afterSeq", 0, "limit", 1));
        assertThat(fixture.json(limited).path("data").path("events")).hasSize(1);
        ToolResult repeated = fixture.call(control, ExternalControlToolSet.TF_EVENT_POLL,
                Map.of("afterSeq", 0, "limit", 1));
        assertThat(fixture.json(repeated).path("data").path("events")).hasSize(1);
        assertThat(fixture.store.size()).isEqualTo(2);
    }

    @Test
    void eventPollWithoutPortReturnsStableUnavailableError() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet noPort = ExternalControlToolSet.builder(fixture.engine, fixture.supervisor)
                .clock(fixture.clock)
                .build();

        ToolResult result = fixture.call(noPort, ExternalControlToolSet.TF_EVENT_POLL,
                Map.of("afterSeq", 0, "limit", 10));

        assertThat(result.code()).isEqualTo("EVENT_STREAM_UNAVAILABLE");
        assertThat(fixture.store.size()).isZero();
    }

    @Test
    void invariantCheckPassesOnValidStateAndFailsOnSyntheticDamagedState() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult pass = fixture.call(control, ExternalControlToolSet.TF_INVARIANT_CHECK, Map.of());
        assertThat(pass.code()).isNull();
        assertThat(fixture.json(pass).path("data").path("overall").asText()).isEqualTo("PASS");

        TurnView damagedTurn = new TurnView("broken-turn", 1, TurnState.COMMITTED,
                2L, 0L, "prompt-v1", ControlTestFixture.BASE,
                ControlTestFixture.BASE.plus(Duration.ofHours(1)), null,
                List.of(), List.of(), null, null);
        EngineStatus damaged = new EngineStatus(2L, 0L, 0, 0, 0, damagedTurn);
        InvariantChecker.Result result = InvariantChecker.inspect(damaged);

        assertThat(result.passed()).isFalse();
        assertThat(result.overall()).isEqualTo("FAIL");
        assertThat(result.checks())
                .filteredOn(check -> "FAIL".equals(check.status()))
                .extracting(InvariantChecker.Check::name)
                .contains("committedHasSnapshot", "committedHasProposal");
    }

    @Test
    void turnGetReturnsDecisionPayloadWithoutTokensAndNotFoundIsStable() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        fixture.engine.registerPlayer(fixture.supervisor,
                new PlayerProfile("p1", "qq-p1", "Player One"), 0L, "seed-reg-p1");
        fixture.engine.registerDirector(fixture.supervisor,
                new PlayerProfile("d1", "qq-d1", "Director"), 1L, "seed-reg-d1");
        fixture.engine.loadWorldFacts(fixture.supervisor, 0L,
                List.of(new WorldFact("f1", 1L, "港口公开事实", Visibility.PUBLIC)),
                2L, "seed-load");
        fixture.engine.openTurn(fixture.supervisor, 3L, "seed-open",
                ControlTestFixture.BASE.plus(Duration.ofHours(2)));

        Actor player = new Actor("p1", ActorRole.PLAYER);
        DecisionDraft draft = fixture.engine.draft(fixture.engine.createDecisionDraft(player,
                new DecisionDraftInput("调查港口", List.of("询问码头工人"),
                        Map.of("focus", "线索"), "harbor", "遇到危险则撤退",
                        ControlTestFixture.BASE.plus(Duration.ofHours(1))),
                1L, "seed-draft").referenceId().orElseThrow()).orElseThrow();
        fixture.engine.confirmDecision(player, draft.draftId(), draft.previewHash(),
                2L, "seed-confirm");

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_TURN_GET, Map.of());
        assertThat(result.code()).isNull();
        JsonNode data = fixture.json(result).path("data");
        assertThat(data.path("decisions")).hasSize(1);
        assertThat(data.path("decisions").get(0).path("fields").path("intent").asText())
                .isEqualTo("调查港口");
        assertThat(result.message()).doesNotContain("token", "Bearer", "Authorization");

        ToolResult missing = fixture.call(control, ExternalControlToolSet.TF_TURN_GET,
                Map.of("turnId", "does-not-exist"));
        assertThat(missing.code()).isEqualTo("NOT_FOUND");
    }
}
