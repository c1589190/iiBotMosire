package io.mosire.iibot.app.delivery;

import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnResultDistributorTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void legacyConstructorPublishesPublicNarrativeAndEachPlayerOutcomeOnce() {
        TaskflowEngine engine = publishedEngine();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        TurnResultDistributor distributor = new TurnResultDistributor(engine,
                new OutboxSender(adapter, Duration.ofSeconds(2)), "group-public",
                Map.of("player-1", "qq-p1"));

        assertThat(distributor.distributeIfReady()).hasSize(2);
        assertThat(distributor.distributeIfReady()).isEmpty();

        assertThat(adapter.sent()).hasSize(2);
        assertThat(adapter.sent().get(0).targetType()).isEqualTo(OutboundMessage.TargetType.GROUP);
        assertThat(adapter.sent().get(0).targetId()).isEqualTo("group-public");
        assertThat(adapter.sent().get(0).textSegments()).containsExactly("港口恢复了平静。");
        assertThat(adapter.sent().get(1).targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
        assertThat(adapter.sent().get(1).targetId()).isEqualTo("qq-p1");
        assertThat(adapter.sent().get(1).textSegments()).containsExactly("你找到了航海日志。");
        assertThat(adapter.sent().get(0).idempotencyKey())
                .isNotEqualTo(adapter.sent().get(1).idempotencyKey());
    }

    @Test
    void persistentLedgerPreventsResendWithFreshDistributorInstance() {
        TaskflowEngine engine = publishedEngine();
        String turnId = engine.turnView().orElseThrow().turnId();
        JsonDeliveryLedger ledger = JsonDeliveryLedger.atFile(
                tempDir.resolve("delivery-ledger.json"));
        FakeMessagingAdapter firstAdapter = new FakeMessagingAdapter();
        TurnResultDistributor first = distributor(engine, firstAdapter, ledger);

        assertThat(first.distributeIfReady()).hasSize(2);
        assertThat(firstAdapter.sent()).hasSize(2);

        JsonDeliveryLedger reloadedLedger = JsonDeliveryLedger.atFile(
                tempDir.resolve("delivery-ledger.json"));
        FakeMessagingAdapter secondAdapter = new FakeMessagingAdapter();
        TurnResultDistributor restarted = distributor(engine, secondAdapter, reloadedLedger);

        assertThat(restarted.distributeIfReady()).isEmpty();
        assertThat(secondAdapter.sent()).isEmpty();
        assertThat(reloadedLedger.load()).containsExactlyInAnyOrder(
                "turn:" + turnId + ":public",
                "turn:" + turnId + ":player:player-1");
    }

    @Test
    void usesExactIndependentPerMessageKeysAndSendsEachOnce() {
        TaskflowEngine engine = publishedEngine();
        String turnId = engine.turnView().orElseThrow().turnId();
        RecordingLedger ledger = new RecordingLedger();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        TurnResultDistributor distributor = distributor(engine, adapter, ledger);

        assertThat(distributor.distributeIfReady()).hasSize(2);
        assertThat(distributor.distributeIfReady()).isEmpty();

        assertThat(ledger.successfulClaims()).containsExactly(
                "turn:" + turnId + ":public",
                "turn:" + turnId + ":player:player-1");
        assertThat(adapter.sent()).extracting(OutboundMessage::idempotencyKey)
                .containsExactly(
                        "public:turn:" + turnId + ":public:reply:0",
                        "private:turn:" + turnId + ":player:player-1:reply:0");
    }

    @Test
    void alreadyClaimedPublicAndOnePlayerAreSkippedWhileOtherPlayerIsSent() {
        TaskflowEngine engine = publishedEngine("港口恢复了平静。", Map.of(
                "player-1", "你找到了航海日志。",
                "player-2", "你听见了钟声。"));
        String turnId = engine.turnView().orElseThrow().turnId();
        RecordingLedger ledger = new RecordingLedger();
        assertThat(ledger.claim("turn:" + turnId + ":public")).isTrue();
        assertThat(ledger.claim("turn:" + turnId + ":player:player-1")).isTrue();
        ledger.clearClaims();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        TurnResultDistributor distributor = distributor(engine, adapter, ledger,
                Map.of("player-1", "qq-p1", "player-2", "qq-p2"));

        assertThat(distributor.distributeIfReady()).hasSize(1);

        assertThat(adapter.sent()).hasSize(1);
        assertThat(adapter.sent().get(0).targetId()).isEqualTo("qq-p2");
        assertThat(adapter.sent().get(0).textSegments()).containsExactly("你听见了钟声。");
        assertThat(ledger.claims()).containsExactly(
                "turn:" + turnId + ":public",
                "turn:" + turnId + ":player:player-1",
                "turn:" + turnId + ":player:player-2");
    }

    @Test
    void blankPublicNarrativeIsNeitherClaimedNorSent() {
        TaskflowEngine engine = publishedEngine("   ", Map.of("player-1", "你找到了航海日志。"));
        String turnId = engine.turnView().orElseThrow().turnId();
        RecordingLedger ledger = new RecordingLedger();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        TurnResultDistributor distributor = distributor(engine, adapter, ledger);

        assertThat(distributor.distributeIfReady()).hasSize(1);
        assertThat(adapter.sent()).hasSize(1);
        assertThat(ledger.claims()).containsExactly(
                "turn:" + turnId + ":player:player-1");
    }

    @Test
    void claimFailureIsPropagatedWithoutSendingAndWithoutOutboxCall() {
        TaskflowEngine engine = publishedEngine();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        DeliveryLedger failing = key -> {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.SAVE,
                    "forced claim failure");
        };
        TurnResultDistributor distributor = distributor(engine, adapter, failing);

        assertThatThrownBy(distributor::distributeIfReady)
                .isInstanceOf(DeliveryLedgerException.class)
                .satisfies(failure -> assertThat(((DeliveryLedgerException) failure).code())
                        .isEqualTo(DeliveryLedgerException.Code.SAVE));
        assertThat(adapter.sent()).isEmpty();
    }

    @Test
    void unknownSendIsNotRetriedAndKeepsEveryClaim() {
        TaskflowEngine engine = publishedEngine();
        RecordingLedger ledger = new RecordingLedger();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(SendReceipt.unknown("TIMEOUT", "unknown")));
        TurnResultDistributor distributor = distributor(engine, adapter, ledger);

        assertThat(distributor.distributeIfReady()).hasSize(2);
        assertThat(distributor.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(2);
        assertThat(ledger.successfulClaims()).hasSize(2);
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.PUBLISHED);
    }

    @Test
    void concurrentCallsOnSameInstanceSendOnlyOnce() throws Exception {
        TaskflowEngine engine = publishedEngine();
        RecordingLedger ledger = new RecordingLedger();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        TurnResultDistributor distributor = distributor(engine, adapter, ledger);
        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return distributor.distributeIfReady().size();
                }));
            }
            start.countDown();
            int sent = 0;
            for (Future<Integer> future : futures) {
                sent += future.get(4, TimeUnit.SECONDS);
            }
            assertThat(sent).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(adapter.sent()).hasSize(2);
        assertThat(ledger.successfulClaims()).hasSize(2);
        assertThat(ledger.claims().stream().distinct()).hasSize(2);
    }

    private static TurnResultDistributor distributor(TaskflowEngine engine,
                                                     FakeMessagingAdapter adapter,
                                                     DeliveryLedger ledger) {
        return distributor(engine, adapter, ledger, Map.of("player-1", "qq-p1"));
    }

    private static TurnResultDistributor distributor(TaskflowEngine engine,
                                                     FakeMessagingAdapter adapter,
                                                     DeliveryLedger ledger,
                                                     Map<String, String> playerQqById) {
        return new TurnResultDistributor(engine,
                new OutboxSender(adapter, Duration.ofSeconds(2)), "group-public",
                playerQqById, ledger);
    }

    private static TaskflowEngine publishedEngine() {
        return publishedEngine("港口恢复了平静。", Map.of("player-1", "你找到了航海日志。"));
    }

    private static TaskflowEngine publishedEngine(String publicNarrative,
                                                  Map<String, String> outcomes) {
        InMemoryEventStore events = new InMemoryEventStore();
        TaskflowEngine engine = new TaskflowEngine(CLOCK, events);
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);

        ok(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "reg-director"));
        Map<String, String> sortedOutcomes = new LinkedHashMap<>();
        outcomes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sortedOutcomes.put(entry.getKey(), entry.getValue()));
        for (String playerId : sortedOutcomes.keySet()) {
            ok(engine.registerPlayer(director,
                    new PlayerProfile(playerId, "qq-" + playerId, "Player " + playerId),
                    engine.status().engineRevision(), "reg-" + playerId));
        }
        ok(engine.loadWorldFacts(director, 0,
                List.of(new WorldFact("fact-1", 1, "港口", Visibility.PUBLIC)),
                engine.status().engineRevision(), "load-world"));
        ok(engine.openTurn(director, engine.status().engineRevision(), "open",
                NOW.plus(Duration.ofMinutes(30))));

        for (String playerId : sortedOutcomes.keySet()) {
            Actor player = new Actor(playerId, ActorRole.PLAYER);
            TurnView opening = engine.turnView().orElseThrow();
            CommandResult draftResult = engine.createDecisionDraft(player,
                    new DecisionDraftInput("调查港口", List.of("询问船工"),
                            Map.of("people", "2"), "北码头", "遇险撤回",
                            NOW.plus(Duration.ofMinutes(20))),
                    opening.revision(), "draft-" + playerId);
            ok(draftResult);
            DecisionDraft draft = engine.draft(draftResult.referenceId().orElseThrow())
                    .orElseThrow();
            TurnView afterDraft = engine.turnView().orElseThrow();
            ok(engine.confirmDecision(player, draft.draftId(), draft.previewHash(),
                    afterDraft.revision(), "confirm-" + playerId));
        }

        TurnView afterConfirm = engine.turnView().orElseThrow();
        ok(engine.lockTurn(director, afterConfirm.revision(), "lock"));
        TurnView locked = engine.turnView().orElseThrow();
        ok(engine.startAdjudication(director, locked.revision(), "start"));
        TurnView adjudicating = engine.turnView().orElseThrow();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION, adjudicating.turnId(),
                engine.status().worldVersion(), publicNarrative,
                outcomes,
                List.of(new WorldDelta("SET_FLAG", "harbor", "calm")),
                List.of("潮汐将在下一回合改变"));
        ok(engine.commitAdjudication(director, adjudicating.revision(), "commit", proposal));
        TurnView committed = engine.turnView().orElseThrow();
        ok(engine.publishTurn(director, committed.revision(), "publish"));
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.PUBLISHED);
        return engine;
    }

    private static void ok(CommandResult result) {
        assertThat(result.ok()).as("command %s: %s", result.code(), result.message()).isTrue();
    }

    private static final class RecordingLedger implements DeliveryLedger {
        private final Set<String> claimed = ConcurrentHashMap.newKeySet();
        private final List<String> claims = new CopyOnWriteArrayList<>();
        private final List<String> successfulClaims = new CopyOnWriteArrayList<>();

        @Override
        public boolean claim(String key) {
            claims.add(key);
            if (!claimed.add(key)) {
                return false;
            }
            successfulClaims.add(key);
            return true;
        }

        List<String> claims() {
            return List.copyOf(claims);
        }

        List<String> successfulClaims() {
            return List.copyOf(successfulClaims);
        }

        void clearClaims() {
            claims.clear();
        }
    }
}
