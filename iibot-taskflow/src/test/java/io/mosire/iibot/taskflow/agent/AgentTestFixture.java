package io.mosire.iibot.taskflow.agent;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** agent 层测试用的纯领域 fixture，不依赖网络或真实 LLM。 */
final class AgentTestFixture {

    static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");

    final Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    final InMemoryEventStore store = new InMemoryEventStore();
    final TaskflowEngine engine = new TaskflowEngine(clock, store);
    final Actor director = new Actor("director-1", ActorRole.DIRECTOR);
    final Actor gm = new Actor("gm-1", ActorRole.GM);
    final Actor p1 = new Actor("player-1", ActorRole.PLAYER);
    final Actor p2 = new Actor("player-2", ActorRole.PLAYER);
    final PlayerProfile p1Profile = new PlayerProfile("player-1", "qq-p1", "Player One");
    final PlayerProfile p2Profile = new PlayerProfile("player-2", "qq-p2", "Player Two");
    final PlayerProfile directorProfile =
            new PlayerProfile("director-1", "qq-director", "Director");
    final List<WorldFact> loadedFacts = new ArrayList<>();

    static AgentTestFixture create() {
        AgentTestFixture f = new AgentTestFixture();
        f.ok(f.engine.registerDirector(f.director, f.directorProfile,
                f.engine.status().engineRevision(), "reg-director"));
        f.ok(f.engine.registerPlayer(f.director, f.p1Profile,
                f.engine.status().engineRevision(), "reg-p1"));
        f.ok(f.engine.registerPlayer(f.director, f.p2Profile,
                f.engine.status().engineRevision(), "reg-p2"));
        return f;
    }

    void loadFacts(WorldFact... facts) {
        loadedFacts.addAll(List.of(facts));
        ok(engine.loadWorldFacts(director, engine.status().worldVersion(), List.of(facts),
                engine.status().engineRevision(), "load-" + engine.status().engineRevision()));
    }

    void openTurn() {
        ok(engine.openTurn(director, engine.status().engineRevision(),
                "open-" + engine.status().engineRevision(),
                clock.instant().plus(Duration.ofHours(2))));
    }

    DecisionDraft createDraftDirect(Actor player, String intent) {
        CommandResult result = ok(engine.createDecisionDraft(player,
                new DecisionDraftInput(intent, List.of("调查"), Map.of("focus", "steady"),
                        "港口", "撤退", clock.instant().plus(Duration.ofMinutes(30))),
                turn().revision(), "draft-direct-" + intent));
        String draftId = result.referenceId().orElseThrow();
        return engine.draft(draftId).orElseThrow();
    }

    void confirmDirect(Actor player, DecisionDraft draft) {
        ok(engine.confirmDecision(player, draft.draftId(), draft.previewHash(),
                turn().revision(), "confirm-direct-" + draft.draftId()));
    }

    void lockDirect() {
        ok(engine.lockTurn(director, turn().revision(), "lock-" + turn().revision()));
    }

    io.mosire.iibot.taskflow.TurnView turn() {
        return engine.turnView().orElseThrow();
    }

    CommandResult ok(CommandResult result) {
        if (!result.ok()) {
            throw new AssertionError("预期成功，实际 " + result.code() + ": " + result.message());
        }
        return result;
    }

    Map<String, MappedIdentity> identities() {
        return Map.of(
                p1Profile.qqId(), new MappedIdentity(p1Profile.qqId(), p1, p1Profile),
                p2Profile.qqId(), new MappedIdentity(p2Profile.qqId(), p2, p2Profile),
                directorProfile.qqId(), new MappedIdentity(directorProfile.qqId(), director,
                        directorProfile));
    }

    BriefingService noopBriefing() {
        return (playerId, question, visibleFacts) -> {
            if (visibleFacts.isEmpty()) {
                return new BriefingResult("", java.util.Set.of());
            }
            return new BriefingResult("可见简报：" + visibleFacts.get(0).content(),
                    java.util.Set.of(visibleFacts.get(0).factId()));
        };
    }
}
