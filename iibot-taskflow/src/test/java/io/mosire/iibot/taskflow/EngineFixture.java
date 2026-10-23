package io.mosire.iibot.taskflow;

import io.mosire.iibot.taskflow.event.InMemoryEventStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 多个测试共享的领域 fixture；只使用本模块纯领域 API。 */
final class EngineFixture {
    static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");

    final MutableClock clock = new MutableClock(BASE);
    final InMemoryEventStore store = new InMemoryEventStore();
    final TaskflowEngine engine = new TaskflowEngine(clock, store);
    final Actor director = new Actor("director-1", ActorRole.DIRECTOR);
    final Actor p1 = new Actor("player-1", ActorRole.PLAYER);
    final Actor p2 = new Actor("player-2", ActorRole.PLAYER);

    static EngineFixture create() {
        EngineFixture fixture = new EngineFixture();
        fixture.expectOk(fixture.engine.registerDirector(fixture.director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                fixture.globalRevision(), "reg-director"));
        fixture.expectOk(fixture.engine.registerPlayer(fixture.director,
                new PlayerProfile("player-1", "qq-p1", "Player One"),
                fixture.globalRevision(), "reg-p1"));
        fixture.expectOk(fixture.engine.registerPlayer(fixture.director,
                new PlayerProfile("player-2", "qq-p2", "Player Two"),
                fixture.globalRevision(), "reg-p2"));
        return fixture;
    }

    long globalRevision() {
        return engine.status().engineRevision();
    }

    long worldVersion() {
        return engine.status().worldVersion();
    }

    long turnRevision() {
        return engine.turnView().orElseThrow().revision();
    }

    TurnView turnView() {
        return engine.turnView().orElseThrow();
    }

    CommandResult expectOk(CommandResult result) {
        if (!result.ok()) {
            throw new AssertionError("期望成功，实际 " + result.code() + ": " + result.message());
        }
        return result;
    }

    CommandResult loadFacts(WorldFact... facts) {
        return expectOk(engine.loadWorldFacts(director, worldVersion(), List.of(facts),
                globalRevision(), "load-facts-" + globalRevision()));
    }

    CommandResult openTurn() {
        return expectOk(engine.openTurn(director, globalRevision(),
                "open-" + globalRevision(), clock.instant().plus(Duration.ofHours(2))));
    }

    DecisionDraft createDraft(Actor player, String intent, String key) {
        CommandResult result = expectOk(engine.createDecisionDraft(player,
                new DecisionDraftInput(intent, List.of("act-" + intent),
                        java.util.Map.of("focus", "steady"), "target-" + intent,
                        "fallback", clock.instant().plus(Duration.ofHours(1))),
                turnRevision(), key));
        String draftId = result.referenceId().orElseThrow();
        return engine.draft(draftId).orElseThrow();
    }

    Decision confirm(Actor player, DecisionDraft draft, String key) {
        CommandResult result = expectOk(engine.confirmDecision(player, draft.draftId(),
                draft.previewHash(), turnRevision(), key));
        String decisionId = result.referenceId().orElseThrow();
        return turnView().decisions().stream()
                .filter(d -> d.decisionId().equals(decisionId))
                .findFirst().orElseThrow();
    }

    void lock() {
        expectOk(engine.lockTurn(director, turnRevision(), "lock-" + turnRevision()));
    }

    void start() {
        expectOk(engine.startAdjudication(director, turnRevision(), "start-" + turnRevision()));
    }
}
