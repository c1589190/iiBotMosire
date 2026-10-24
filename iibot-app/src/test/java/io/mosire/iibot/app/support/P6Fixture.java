package io.mosire.iibot.app.support;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.publication.EventSourcedPostStore;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.records.TurnRecordProjector;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchStatus;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageView;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.PostService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 发布/记录测试的离线领域夹具。
 *
 * <p>只使用内存 engine、进程内裁定包与临时 events.db；不连接真实 QQ/LLM/外部应用。</p>
 */
public final class P6Fixture implements AutoCloseable {

    public static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    public static final Clock CLOCK = Clock.fixed(BASE, ZoneOffset.UTC);
    public static final Actor DIRECTOR = new Actor("director-1", ActorRole.DIRECTOR);
    public static final Actor GM = new Actor("gm-1", ActorRole.GM);
    public static final Actor SUPERVISOR = new Actor("supervisor-1", ActorRole.SUPERVISOR);

    public final Path dataDir;
    public final Clock clock;
    public final EventContentStore contentStore;
    public final DomainEventJournal journal;
    public final TaskflowEngine engine;
    public final PostService postService;
    public final ActionPoolService actionPoolService;
    public final ActionPackageService packageService;

    private final Actor director;
    private final List<String> seededPlayerIds = new ArrayList<>();

    public static P6Fixture open(Path dataDir) {
        return open(dataDir, SqliteDomainEventJournal::new);
    }

    /** 零玩家、无活动回合的 opening 夹具；只注册 director。 */
    public static P6Fixture openForOpening(Path dataDir) {
        return new P6Fixture(dataDir, SqliteDomainEventJournal::new, false);
    }

    public static P6Fixture open(Path dataDir,
                                 Function<EventContentStore, DomainEventJournal> journalFactory) {
        return new P6Fixture(dataDir, journalFactory, true);
    }

    public static P6Fixture openForOpening(Path dataDir,
                                            Function<EventContentStore, DomainEventJournal> journalFactory) {
        return new P6Fixture(dataDir, journalFactory, false);
    }

    private P6Fixture(Path dataDir,
                      Function<EventContentStore, DomainEventJournal> journalFactory,
                      boolean seedPlayersAndTurn) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException failure) {
            throw new IllegalStateException("创建 P6 测试目录失败", failure);
        }
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.clock = CLOCK;
        this.contentStore = new EventContentStore(this.dataDir.resolve("events.db"));
        this.journal = journalFactory.apply(contentStore);
        this.postService = new PostService(
                new EventSourcedPostStore(contentStore, journal, clock), clock);
        this.engine = new TaskflowEngine(clock, new InMemoryEventStore());
        this.actionPoolService = new ActionPoolService(clock);
        this.packageService = new ActionPackageService(
                actionPoolService, new DecisionPackages(), clock);

        this.director = DIRECTOR;
        requireOk(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "p6-register-director"), "register director");
        if (seedPlayersAndTurn) {
            seedPlayer("player-1", "qq-p1", "Player One");
            seedPlayer("player-2", "qq-p2", "Player Two");
            requireOk(engine.loadWorldFacts(director, engine.status().worldVersion(),
                    List.of(new WorldFact("fact-1", engine.status().worldVersion() + 1,
                            "雾港世界事实：北码头封闭。", Visibility.PUBLIC)),
                    engine.status().engineRevision(), "p6-load-facts"), "load facts");
            requireOk(engine.openTurn(director, engine.status().engineRevision(),
                    "p6-open-turn", clock.instant().plusSeconds(3_600)), "open turn");
        }
    }

    private void seedPlayer(String playerId, String qqId, String displayName) {
        requireOk(engine.registerPlayer(director,
                new PlayerProfile(playerId, qqId, displayName),
                engine.status().engineRevision(), "p6-register-" + playerId),
                "register player " + playerId);
        seededPlayerIds.add(playerId);
    }

    public List<String> seededPlayerIds() {
        return List.copyOf(seededPlayerIds);
    }

    /** 仅供 opening/records 测试：在开场创建后补一个空回合，验证 openings 不混入 turn 树。 */
    public void openTurnForProjection() {
        requireOk(engine.openTurn(director, engine.status().engineRevision(),
                "p6-open-turn-for-projection", clock.instant().plusSeconds(3_600)),
                "open turn for projection");
    }

    public String turnId() {
        return engine.turnView().orElseThrow().turnId();
    }

    public int turnNo() {
        return engine.turnView().orElseThrow().turnNo();
    }

    public PublicationOrchestrator publicationOrchestrator(LlmClient llm) {
        return new PublicationOrchestrator(postService, packageService, llm, engine,
                contentStore, journal, clock);
    }

    public TurnRecordProjector projector(PublicationOrchestrator publications) {
        return new TurnRecordProjector(dataDir, engine, packageService, postService,
                publications);
    }

    public ActionBatch commitBatch(String title, String key,
                                   List<ActionInput> inputs) {
        List<PlayerAction> actions = new ArrayList<>();
        for (ActionInput input : inputs) {
            actions.add(actionPoolService.submit(new Actor(input.playerId(), ActorRole.PLAYER),
                    input.text(), "chat", key + ":action:" + input.playerId()
                            + ":" + actions.size()));
        }
        PackageView view = packageService.create(GM, title,
                actions.stream().map(PlayerAction::actionId).toList(),
                3_600, key + ":package");
        Set<Integer> approved = new LinkedHashSet<>();
        for (int index = 0; index < actions.size(); index++) {
            approved.add(index);
        }
        packageService.decide(GM, view.packageId(), approved, key + ":decision");
        String turnId = turnId();
        CommitReceipt receipt = packageService.commit(GM, view.packageId(),
                batch -> batch.withTurnId(turnId)
                        .committed("批次执行结果：" + title));
        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        return packageService.batches().stream()
                .filter(batch -> batch.batchId().equals(receipt.revisionRef()))
                .findFirst()
                .orElseThrow();
    }

    public ActionBatch commitOne(String title, String key, String playerId, String text) {
        return commitBatch(title, key, List.of(new ActionInput(playerId, text)));
    }

    public List<StoredEvent> publicationEvents() {
        return contentStore.readByTypePrefix(DomainEventTypes.PUBLICATION_PREFIX);
    }

    public static ActionInput action(String playerId, String text) {
        return new ActionInput(playerId, text);
    }

    private static void requireOk(CommandResult result, String action) {
        assertThat(result.ok()).as(action + " result=%s", result).isTrue();
    }

    @Override
    public void close() {
        contentStore.close();
    }

    public record ActionInput(String playerId, String text) {
        public ActionInput {
            assertThat(playerId).isNotBlank();
            assertThat(text).isNotBlank();
        }
    }
}
