package io.mosire.iibot.app.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.iibot.app.action.EventSourcedActionPoolStore;
import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchStatus;
import io.mosire.iibot.taskflow.decision.ActionPackageException;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageCommand;
import io.mosire.iibot.taskflow.decision.PackageCommandEvent;
import io.mosire.iibot.taskflow.decision.PackageView;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventAppendRequest;
import io.mosire.iibot.taskflow.event.EventContent;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P5：events.db 重开后通过 ActionPackageRecoveryService 重建包/批次，commit replay 不重跑 executor。 */
class ActionPackageRecoveryServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_1 = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor PLAYER_2 = new Actor("player-2", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @TempDir
    Path tempDir;

    @Test
    void reopensEventsDbAndRecoversCommittedAndProposedPackagesAndBatches() {
        Path db = tempDir.resolve("package-recovery.db");
        String committedPackage;
        String proposedPackage;
        String batchId;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService actions = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, journal, CLOCK), CLOCK);
            PlayerAction a1 = actions.submit(PLAYER_1, "恢复包行动一", "chat", "recovery-a1");
            PlayerAction a2 = actions.submit(PLAYER_2, "恢复包行动二", "chat", "recovery-a2");
            ActionPackageService service = new ActionPackageService(
                    actions, new io.mosire.agentlib.decision.DecisionPackages(),
                    CLOCK, journal);
            PackageView committed = service.create(GM, "恢复已提交包",
                    List.of(a1.actionId()), 3600, "recovery-create-1");
            committedPackage = committed.packageId();
            service.decide(GM, committedPackage, Set.of(0), "批准");
            CommitReceipt receipt = service.commit(GM, committedPackage,
                    batch -> batch.committed("恢复批次"));
            assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
            batchId = receipt.revisionRef();
            assertThat(batchId).isNotBlank();

            PackageView proposed = service.create(GM, "恢复提议包",
                    List.of(a2.actionId()), 3600, "recovery-create-2");
            proposedPackage = proposed.packageId();
        }

        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService actions = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, journal, CLOCK), CLOCK);
            ActionPackageRecoveryService recovery = new ActionPackageRecoveryService(
                    store, journal, CLOCK);
            ActionPackageService recovered = recovery.recover(actions);

            assertThat(recovered.get(committedPackage, false).orElseThrow().state())
                    .isEqualTo(PackageState.COMMITTED);
            assertThat(recovered.get(proposedPackage, false).orElseThrow().state())
                    .isEqualTo(PackageState.PROPOSED);
            assertThat(recovered.batches()).singleElement()
                    .satisfies(batch -> assertThat(batch.batchId()).isEqualTo(batchId));
            assertThat(actions.listForGm()).anySatisfy(action -> {
                if (action.text().equals("恢复包行动一")) {
                    assertThat(action.status()).isEqualTo(ActionStatus.COMMITTED);
                }
            });
            assertThat(actions.listForGm()).anySatisfy(action -> {
                if (action.text().equals("恢复包行动二")) {
                    assertThat(action.status()).isEqualTo(ActionStatus.QUEUED);
                }
            });

            AtomicInteger executions = new AtomicInteger();
            CommitReceipt replay = recovered.commit(GM, committedPackage, batch -> {
                executions.incrementAndGet();
                return batch.committed("不应重跑");
            });
            assertThat(replay.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
            assertThat(replay.revisionRef()).isEqualTo(batchId);
            assertThat(executions).hasValue(0);
            assertThat(recovered.batches()).hasSize(1);
        }
    }

    @Test
    void rejectedExecutorBatchSurvivesEventsDbReopenWithoutRerun() {
        Path db = tempDir.resolve("package-rejected-recovery.db");
        String batchId;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService actions = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, journal, CLOCK), CLOCK);
            PlayerAction action = actions.submit(PLAYER_1, "拒绝批次行动", "chat", "rejected-a1");
            ActionPackageService service = new ActionPackageService(
                    actions, new io.mosire.agentlib.decision.DecisionPackages(),
                    CLOCK, journal);
            PackageView created = service.create(GM, "拒绝批次包",
                    List.of(action.actionId()), 3600, "rejected-create");
            service.decide(GM, created.packageId(), Set.of(0), "批准");
            CommitReceipt receipt = service.commit(GM, created.packageId(), batch -> {
                throw new IllegalStateException("executor boom");
            });
            assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.REJECTED);
            batchId = receipt.revisionRef();
            assertThat(batchId).isNotBlank();
            assertThat(service.batches()).singleElement().satisfies(batch -> {
                assertThat(batch.batchId()).isEqualTo(batchId);
                assertThat(batch.status()).isEqualTo(ActionBatchStatus.REJECTED);
            });
        }

        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService actions = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, journal, CLOCK), CLOCK);
            ActionPackageService recovered = new ActionPackageRecoveryService(
                    store, journal, CLOCK).recover(actions);
            assertThat(recovered.batches()).singleElement().satisfies(batch -> {
                assertThat(batch.batchId()).isEqualTo(batchId);
                assertThat(batch.status()).isEqualTo(ActionBatchStatus.REJECTED);
            });
            AtomicInteger executions = new AtomicInteger();
            CommitReceipt replay = recovered.commit(GM, recovered.batches().get(0).packageId(),
                    batch -> {
                        executions.incrementAndGet();
                        return batch.committed("不应重跑");
                    });
            assertThat(replay.revisionRef()).isEqualTo(batchId);
            assertThat(executions).hasValue(0);
            assertThat(recovered.batches()).hasSize(1);
        }
    }

    @Test
    void paginatedRecoveryReadsMoreThanOneThousandEventsAndNonPagedReadFailsClosed() {
        Path db = tempDir.resolve("package-pagination.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService actions = new ActionPoolService(CLOCK);
            ActionPackageService service = new ActionPackageService(
                    actions, new io.mosire.agentlib.decision.DecisionPackages(),
                    CLOCK, journal);
            List<PlayerAction> allActions = new ArrayList<>();
            for (int index = 0; index <= 600; index++) {
                allActions.add(actions.submit(PLAYER_1, "分页行动-" + index,
                        "chat", "page-a-" + index));
            }
            PackageView created = service.create(GM, "分页包",
                    List.of(allActions.get(0).actionId()), 3600, "page-create");
            for (int index = 1; index <= 600; index++) {
                service.append(GM, created.packageId(),
                        List.of(allActions.get(index).actionId()));
            }
            long commandEventCount = store.readAfter(0, 10_000).stream()
                    .filter(event -> event.eventType().startsWith(
                            DomainEventTypes.PACKAGE_COMMAND_PREFIX))
                    .count();
            assertThat(commandEventCount).isGreaterThan(1_000);
            assertThatThrownBy(() -> store.readByTypePrefix(
                    DomainEventTypes.PACKAGE_COMMAND_PREFIX, 1_000))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("超过 limit");

            ActionPackageService recovered = new ActionPackageRecoveryService(
                    store, journal, CLOCK).recover(actions);
            assertThat(recovered.get(created.packageId(), false).orElseThrow().items())
                    .hasSize(601);
        }
    }

    @Test
    void moreThanTenThousandPackageCommandEventsFailsClosed() throws Exception {
        Path db = tempDir.resolve("package-over-limit.db");
        try (EventContentStore store = new EventContentStore(db)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String contentHash = java.util.HexFormat.of().formatHex(
                    digest.digest("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + db)) {
                connection.setAutoCommit(false);
                try (java.sql.PreparedStatement events = connection.prepareStatement(
                        "INSERT INTO events(seq, ts, type, agent, payload, correlation_id, "
                                + "event_version, visibility, content_hash, payload_json, "
                                + "content_missing) VALUES (?,?,?,?,?,?,?,?,?,?,0)");
                     java.sql.PreparedStatement contents = connection.prepareStatement(
                             "INSERT INTO event_contents(seq, content_type, content_json) "
                                     + "VALUES (?,?,?)")) {
                    for (int index = 1; index <= 10_001; index++) {
                        events.setLong(1, index);
                        events.setString(2, CLOCK.instant().toString());
                        events.setString(3, DomainEventTypes.PACKAGE_COMMAND_STARTED);
                        events.setString(4, "system:test");
                        events.setString(5, "{}");
                        events.setString(6, "");
                        events.setLong(7, EventContentStore.CURRENT_EVENT_VERSION);
                        events.setString(8, EventVisibility.GM.name());
                        events.setString(9, contentHash);
                        events.setString(10, "{}");
                        events.addBatch();
                        contents.setLong(1, index);
                        contents.setString(2, EventContentType.JSON.name());
                        contents.setString(3, "{}");
                        contents.addBatch();
                    }
                    events.executeBatch();
                    contents.executeBatch();
                }
                connection.commit();
            }
            ActionPoolService actions = new ActionPoolService(CLOCK);
            assertThatThrownBy(() -> new ActionPackageRecoveryService(
                    store, new SqliteDomainEventJournal(store), CLOCK).recover(actions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[PACKAGE_RECOVERY_INCOMPLETE]");
        }
    }

    @Test
    void eventTypeAndContentPhaseMismatchFailsClosed() {
        Path db = tempDir.resolve("package-phase-mismatch.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            PackageCommand command = new PackageCommand(PackageCommand.CommandType.CREATE,
                    "pkg-mismatch", "gm-1", ActorRole.GM, "title",
                    "gm-package:gm-1", List.of(), null, null, null, "", 3600,
                    "mismatch-key", null, null);
            journal.append(new DomainEvent(CLOCK.instant(),
                    DomainEventTypes.PACKAGE_COMMAND_STARTED, ActorRole.SUPERVISOR,
                    "system:test", "", null, EventVisibility.GM,
                    new PackageCommandEvent(PackageCommandEvent.Phase.APPLIED,
                            command, null), java.util.Map.of()));
            ActionPoolService actions = new ActionPoolService(CLOCK);
            assertThatThrownBy(() -> new ActionPackageRecoveryService(
                    store, journal, CLOCK).recover(actions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[PACKAGE_RECOVERY_CORRUPT]")
                    .hasMessageContaining("type 与 phase 不一致");
        }
    }
}
