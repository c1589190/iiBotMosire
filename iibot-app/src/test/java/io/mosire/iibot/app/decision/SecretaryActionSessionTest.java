package io.mosire.iibot.app.decision;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.action.InMemoryActionPoolStore;
import io.mosire.iibot.taskflow.agent.BriefingResult;
import io.mosire.iibot.taskflow.agent.BriefingService;
import io.mosire.iibot.taskflow.agent.SecretaryActionTools;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretaryActionSessionTest {

    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(BASE, ZoneOffset.UTC);

    @Test
    void secretaryCanSubmitNaturalLanguageActionWhenNoTurnExists() {
        TaskflowEngine engine = new TaskflowEngine(CLOCK, new InMemoryEventStore());
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        Actor player = new Actor("player-1", ActorRole.PLAYER);
        assertThat(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "register-director").ok()).isTrue();
        assertThat(engine.registerPlayer(director,
                new PlayerProfile("player-1", "qq-player", "Player One"),
                engine.status().engineRevision(), "register-player").ok()).isTrue();
        assertThat(engine.turnView()).isEmpty();

        ActionPoolService pool = new ActionPoolService(new InMemoryActionPoolStore(), CLOCK);
        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.toolCall("submit-1", SecretaryActionTools.SUBMIT,
                        Map.of("text", "回合外提交：先调查北码头。",
                                "source", "private-chat", "idempotencyKey", "outside-turn-1")),
                LlmResponse.text("已记录你的行动，等待 GM 裁定。"));
        BriefingService briefing = (playerId, question, visibleFacts) ->
                new BriefingResult("", java.util.Set.of());
        SecretarySession session = new SecretarySession("player-1", player, engine, llm,
                briefing, director, CLOCK, pool);

        assertThat(session.chat("我想先调查北码头"))
                .isEqualTo("已记录你的行动，等待 GM 裁定。");

        assertThat(engine.turnView()).isEmpty();
        assertThat(pool.listForPlayer("player-1")).hasSize(1);
        assertThat(pool.listForPlayer("player-1").get(0).text())
                .isEqualTo("回合外提交：先调查北码头。");
        assertThat(pool.listForPlayer("player-1").get(0).status().name())
                .isEqualTo("QUEUED");
        assertThat(session.permissions().resourceScopes()
                .declaredScope(ActionPoolStore.RESOURCE_NAMESPACE)).isNotNull();
        assertThat(session.toolInvocations()).hasSize(1);
    }
}
