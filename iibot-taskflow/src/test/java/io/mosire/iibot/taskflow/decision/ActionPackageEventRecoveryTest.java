package io.mosire.iibot.taskflow.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolSnapshot;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.InMemoryActionPoolStore;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P5 包命令事件/批次完整内容、恢复重放不重跑 executor、失败与过期语义。 */
class ActionPackageEventRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_1 = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor PLAYER_2 = new Actor("player-2", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void packageCommandsAndBatchEventsCarryFullContentWhileSummariesHideActionText() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        List<DomainEvent> events = new ArrayList<>();
        DomainEventJournal journal = record(events);
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, journal);

        PlayerAction a1 = actions.submit(PLAYER_1, "已批准行动正文-甲", "chat", "p5-a1");
        PlayerAction a2 = actions.submit(PLAYER_1, "未批准行动正文-乙", "chat", "p5-a2");
        PlayerAction a3 = actions.submit(PLAYER_2, "另一玩家行动正文-丙", "chat", "p5-a3");
        PackageView created = service.create(GM, "P5 包",
                List.of(a1.actionId(), a2.actionId()), 3600, "p5-create");
        service.append(GM, created.packageId(), List.of(a3.actionId()));
        service.decide(GM, created.packageId(), Set.of(0, 2), "批准甲丙");
        AtomicInteger executions = new AtomicInteger();
        CommitReceipt receipt = service.commit(GM, created.packageId(), batch -> {
            executions.incrementAndGet();
            return batch.committed("P5 批次已提交");
        });
        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        assertThat(executions).hasValue(1);
        assertThat(service.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.actionIds()).containsExactly(a1.actionId(), a3.actionId());
            assertThat(batch.texts()).containsExactly("已批准行动正文-甲", "另一玩家行动正文-丙");
        });

        assertThat(events).extracting(DomainEvent::eventType)
                .contains(DomainEventTypes.PACKAGE_COMMAND_STARTED,
                        DomainEventTypes.PACKAGE_COMMAND_APPLIED,
                        DomainEventTypes.BATCH_COMPLETED);
        assertThat(events).filteredOn(event -> event.eventType().equals(
                        DomainEventTypes.PACKAGE_COMMAND_APPLIED))
                .allSatisfy(event -> {
                    assertThat(event.content()).isInstanceOf(PackageCommandEvent.class);
                    PackageCommandEvent command = (PackageCommandEvent) event.content();
                    assertThat(command.phase()).isEqualTo(PackageCommandEvent.Phase.APPLIED);
                    assertThat(event.summary().toString())
                            .doesNotContain("已批准行动正文-甲")
                            .doesNotContain("未批准行动正文-乙")
                            .doesNotContain("另一玩家行动正文-丙");
                });
        assertThat(events).filteredOn(event -> event.eventType()
                .equals(DomainEventTypes.BATCH_COMPLETED)).hasSize(1);
        DomainEvent batchEvent = events.stream()
                .filter(event -> event.eventType().equals(DomainEventTypes.BATCH_COMPLETED))
                .findFirst().orElseThrow();
        assertThat(batchEvent.content()).isInstanceOf(ActionBatch.class);
        ActionBatch committedBatch = (ActionBatch) batchEvent.content();
        assertThat(committedBatch.status()).isEqualTo(ActionBatchStatus.COMMITTED);
        assertThat(committedBatch.texts()).contains("已批准行动正文-甲", "另一玩家行动正文-丙")
                .doesNotContain("未批准行动正文-乙");
        assertThat(batchEvent.summary().toString())
                .doesNotContain("已批准行动正文-甲")
                .doesNotContain("另一玩家行动正文-丙");
        assertThat(actions.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        assertThat(actions.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(actions.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);

        // commit replay 不重跑 executor，也不新增任何命令/批次事件。
        int eventCountBeforeReplay = events.size();
        CommitReceipt replay = service.commit(GM, created.packageId(), batch -> {
            executions.incrementAndGet();
            return batch.committed("不应再次执行");
        });
        assertThat(replay.revisionRef()).isEqualTo(receipt.revisionRef());
        assertThat(executions).hasValue(1);
        assertThat(service.batches()).hasSize(1);
        assertThat(events).hasSize(eventCountBeforeReplay);
    }

    @Test
    void recoveryRebuildsCommittedAndProposedPackagesAndReplaysCommitWithoutExecutor() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        List<DomainEvent> events = new ArrayList<>();
        DomainEventJournal journal = record(events);
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, journal);
        PlayerAction a1 = actions.submit(PLAYER_1, "恢复行动一", "chat", "recover-a1");
        PlayerAction a2 = actions.submit(PLAYER_2, "恢复行动二", "chat", "recover-a2");
        PlayerAction a3 = actions.submit(PLAYER_1, "恢复行动三", "chat", "recover-a3");

        PackageView committed = service.create(GM, "恢复已提交包",
                List.of(a1.actionId()), 3600, "recover-create-1");
        service.decide(GM, committed.packageId(), Set.of(0), "批准");
        CommitReceipt receipt = service.commit(GM, committed.packageId(),
                batch -> batch.committed("恢复批次"));
        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        PackageView proposed = service.create(GM, "恢复提议包",
                List.of(a2.actionId(), a3.actionId()), 3600, "recover-create-2");

        List<PackageCommandEvent> commandEvents = commandEvents(events);
        long originalCreateDeadline = commandEvents.stream()
                .filter(event -> event.phase() == PackageCommandEvent.Phase.APPLIED
                        && event.command().commandType() == PackageCommand.CommandType.CREATE
                        && event.command().packageId().equals(committed.packageId()))
                .findFirst().orElseThrow().command().deadlineEpochMs();
        ActionPackageService recovered = ActionPackageService.recover(
                actions, CLOCK, commandEvents, journal);
        assertThat(recovered.get(committed.packageId(), false).orElseThrow().state())
                .isEqualTo(PackageState.COMMITTED);
        // 未来 deadline 必须精确保留，而不是被 replay floor 改写。
        assertThat(recovered.get(committed.packageId(), false).orElseThrow()
                .deadlineEpochMs()).isEqualTo(originalCreateDeadline);
        assertThat(recovered.get(proposed.packageId(), false).orElseThrow().state())
                .isEqualTo(PackageState.PROPOSED);
        assertThat(recovered.batches()).singleElement()
                .satisfies(batch -> assertThat(batch.packageId())
                        .isEqualTo(committed.packageId()));
        AtomicInteger executions = new AtomicInteger();
        CommitReceipt replay = recovered.commit(GM, committed.packageId(), batch -> {
            executions.incrementAndGet();
            return batch.committed("不应执行");
        });
        assertThat(replay.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        assertThat(executions).hasValue(0);
        assertThat(recovered.batches()).hasSize(1);
    }

    @Test
    void recoveryRejectsStartedWithoutAppliedAndExpiresOriginalDeadline() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        List<DomainEvent> events = new ArrayList<>();
        DomainEventJournal journal = record(events);
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, journal);
        PlayerAction action = actions.submit(PLAYER_1, "过期候选行动", "chat", "expire-a1");
        PackageView created = service.create(GM, "过期包",
                List.of(action.actionId()), 3600, "expire-create");
        List<PackageCommandEvent> commandEvents = commandEvents(events);
        PackageCommandEvent createApplied = commandEvents.stream()
                .filter(event -> event.phase() == PackageCommandEvent.Phase.APPLIED
                        && event.command().commandType() == PackageCommand.CommandType.CREATE)
                .findFirst().orElseThrow();

        PackageCommandEvent onlyStarted = new PackageCommandEvent(
                PackageCommandEvent.Phase.STARTED, createApplied.command(), null);
        assertThatThrownBy(() -> ActionPackageService.recover(
                actions, CLOCK, List.of(onlyStarted), journal))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.PACKAGE_RECOVERY_INCOMPLETE));

        PackageCommand original = createApplied.command();
        PackageCommand expiredCommand = new PackageCommand(original.commandType(),
                original.packageId(), original.actorId(), original.actorRole(),
                original.title(), original.proposerKey(), original.items(), original.index(),
                original.approvedIndexes(), original.approve(), original.note(),
                CLOCK.millis() - 1_000L, original.idempotencyKey(), original.receipt(),
                original.batch());
        PackageCommandEvent expiredStarted = new PackageCommandEvent(
                PackageCommandEvent.Phase.STARTED, expiredCommand, null);
        PackageCommandEvent expired = new PackageCommandEvent(
                PackageCommandEvent.Phase.APPLIED, expiredCommand, null);
        ActionPackageService recovered = ActionPackageService.recover(
                actions, CLOCK, List.of(expiredStarted, expired), journal);
        assertThat(recovered.get(created.packageId(), false).orElseThrow().state())
                .isEqualTo(PackageState.EXPIRED);
    }

    @Test
    void startedWriteFailureLeavesZeroChangeAndAppliedFailureFailStops() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        PlayerAction action = actions.submit(PLAYER_1, "失败路径行动", "chat", "fail-a1");
        List<DomainEvent> startedOnly = new ArrayList<>();
        DomainEventJournal startedFails = event -> {
            if (event.eventType().equals(DomainEventTypes.PACKAGE_COMMAND_STARTED)) {
                throw new IllegalStateException("模拟 STARTED 写失败");
            }
            startedOnly.add(event);
            return startedOnly.size();
        };
        ActionPackageService startedFailure = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, startedFails);
        assertThatThrownBy(() -> startedFailure.create(GM, "STARTED 失败包",
                List.of(action.actionId()), 3600, "started-fail"))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.EVENT_JOURNAL_UNAVAILABLE));
        assertThat(startedFailure.list()).isEmpty();
        assertThat(actions.getForGm(action.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);

        List<DomainEvent> appliedDenied = new ArrayList<>();
        DomainEventJournal appliedFails = event -> {
            if (event.eventType().equals(DomainEventTypes.PACKAGE_COMMAND_APPLIED)) {
                throw new IllegalStateException("模拟 APPLIED 写失败");
            }
            appliedDenied.add(event);
            return appliedDenied.size();
        };
        ActionPackageService appliedFailure = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, appliedFails);
        assertThatThrownBy(() -> appliedFailure.create(GM, "APPLIED 失败包",
                List.of(action.actionId()), 3600, "applied-fail"))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.EVENT_JOURNAL_UNAVAILABLE));
        assertThat(appliedFailure.journalFailed()).isTrue();
        assertThatThrownBy(() -> appliedFailure.append(GM, "pkg-any", List.of()))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.EVENT_JOURNAL_UNAVAILABLE));
    }

    @Test
    void twoPackagesInSameTurnProduceTwoIndependentBatches() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, record(events));
        PlayerAction a1 = actions.submit(PLAYER_1, "多批次行动一", "chat", "multi-a1");
        PlayerAction a2 = actions.submit(PLAYER_1, "多批次行动二", "chat", "multi-a2");
        PlayerAction a3 = actions.submit(PLAYER_2, "多批次行动三", "chat", "multi-a3");

        // AgentLib 的 proposer 同一时刻只允许一个未决包：先提交第一包再建第二包。
        PackageView first = service.create(GM, "第一批次包",
                List.of(a1.actionId()), 3600, "multi-create-1");
        service.decide(GM, first.packageId(), Set.of(0), "批一");
        service.commit(GM, first.packageId(), batch -> batch.committed("batch-one"));
        PackageView second = service.create(GM, "第二批次包",
                List.of(a2.actionId(), a3.actionId()), 3600, "multi-create-2");
        service.decide(GM, second.packageId(), Set.of(0, 1), "批二");
        service.commit(GM, second.packageId(), batch -> batch.committed("batch-two"));

        assertThat(service.batches()).hasSize(2);
        assertThat(service.batches()).extracting(ActionBatch::packageId)
                .containsExactly(first.packageId(), second.packageId());
        assertThat(events).filteredOn(event -> event.eventType()
                        .equals(DomainEventTypes.BATCH_COMPLETED)).hasSize(2);
        assertThat(events).filteredOn(event -> event.eventType()
                        .equals(DomainEventTypes.BATCH_COMPLETED)).allSatisfy(event ->
                assertThat(((ActionBatch) event.content()).texts())
                        .doesNotContain("未执行正文"));
    }

    @Test
    void actionPoolProjectionFailureMarksPendingThenReconcileRepairsAndStartupFailureIsFatal() {
        FlakyActionPoolStore flaky = new FlakyActionPoolStore();
        ActionPoolService actions = new ActionPoolService(flaky, CLOCK);
        PlayerAction action = actions.submit(PLAYER_1, "投影失败行动", "chat", "projection-a1");
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, record(events));

        flaky.failSaves = true;
        PackageView created = service.create(GM, "投影失败包",
                List.of(action.actionId()), 3600, "projection-create");
        assertThat(created.state()).isEqualTo(PackageState.PROPOSED);
        assertThat(service.actionReconcilePending()).isTrue();
        assertThat(actions.getForGm(action.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);

        flaky.failSaves = false;
        service.reconcileActionsFromPackages();
        assertThat(service.actionReconcilePending()).isFalse();
        assertThat(actions.getForGm(action.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        service.decide(GM, created.packageId(), Set.of(0), "批准");
        flaky.failSaves = true;
        CommitReceipt receipt = service.commit(GM, created.packageId(),
                batch -> batch.committed("投影失败但包成功"));
        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        assertThat(service.actionReconcilePending()).isTrue();
        assertThat(actions.getForGm(action.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        flaky.failSaves = false;
        service.reconcileActionsFromPackages();
        assertThat(service.actionReconcilePending()).isFalse();
        assertThat(actions.getForGm(action.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);

        // 启动恢复时 ActionPool 缺少包内行动 => 必须 fail-closed，而不是带病启动。
        ActionPoolService emptyActions = new ActionPoolService(CLOCK);
        assertThatThrownBy(() -> ActionPackageService.recover(
                emptyActions, CLOCK, commandEvents(events), record(events)))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.PACKAGE_ACTION_RECONCILE_FAILED));
    }

    @Test
    void executorThrowNullOrNonCommittedKeepsSameBatchThroughLiveAndRecovery() {
        List<ActionBatchExecutor> executors = List.of(
                batch -> {
                    throw new IllegalStateException("executor boom");
                },
                batch -> null,
                batch -> batch.withStatus(ActionBatchStatus.CONFLICTED, "conflict"));
        List<CommitReceipt.Status> expected = List.of(
                CommitReceipt.Status.REJECTED,
                CommitReceipt.Status.REJECTED,
                CommitReceipt.Status.CONFLICTED);
        for (int index = 0; index < executors.size(); index++) {
            CommitReceipt.Status expectedStatus = expected.get(index);
            String actionText = "执行失败行动-" + index;
            ActionPoolService actions = new ActionPoolService(CLOCK);
            PlayerAction action = actions.submit(PLAYER_1,
                    actionText, "chat", "executor-a-" + index);
            List<DomainEvent> events = new ArrayList<>();
            ActionPackageService service = new ActionPackageService(
                    actions, new DecisionPackages(), CLOCK, record(events));
            PackageView created = service.create(GM, "执行失败包-" + index,
                    List.of(action.actionId()), 3600, "executor-create-" + index);
            service.decide(GM, created.packageId(), Set.of(0), "批准");
            CommitReceipt receipt = service.commit(
                    GM, created.packageId(), executors.get(index));
            assertThat(receipt.status()).isEqualTo(expectedStatus);
            assertThat(receipt.revisionRef()).isNotBlank();
            assertThat(service.batches()).singleElement().satisfies(batch -> {
                assertThat(batch.batchId()).isEqualTo(receipt.revisionRef());
                assertThat(batch.status().name()).isEqualTo(expectedStatus.name());
            });
            assertThat(events).filteredOn(event -> event.eventType()
                    .equals(DomainEventTypes.BATCH_COMPLETED)).singleElement()
                    .satisfies(event -> {
                        ActionBatch batch = (ActionBatch) event.content();
                        assertThat(batch.batchId()).isEqualTo(receipt.revisionRef());
                        assertThat(batch.status().name()).isEqualTo(expectedStatus.name());
                        assertThat(event.summary().toString()).doesNotContain(actionText);
                    });

            List<DomainEvent> recoveryEvents = new ArrayList<>();
            ActionPackageService recovered = ActionPackageService.recover(
                    actions, CLOCK, commandEvents(events), record(recoveryEvents));
            assertThat(recovered.batches()).singleElement().satisfies(batch -> {
                assertThat(batch.batchId()).isEqualTo(receipt.revisionRef());
                assertThat(batch.status().name()).isEqualTo(expectedStatus.name());
            });
            AtomicInteger executions = new AtomicInteger();
            CommitReceipt replay = recovered.commit(GM, created.packageId(), batch -> {
                executions.incrementAndGet();
                return batch.committed("不应再执行");
            });
            assertThat(replay.revisionRef()).isEqualTo(receipt.revisionRef());
            assertThat(executions).hasValue(0);
            assertThat(recoveryEvents).isEmpty();
            assertThat(recovered.batches()).hasSize(1);
        }
    }

    @Test
    void failedPhaseMustMatchStartedCommandBase() {
        PackageCommand first = new PackageCommand(PackageCommand.CommandType.CREATE,
                "pkg-first", "gm-1", ActorRole.GM, "title", "gm-package:gm-1",
                List.of(), null, null, null, "", 3600, "key-a", null, null);
        PackageCommand second = new PackageCommand(PackageCommand.CommandType.CREATE,
                "pkg-second", "gm-1", ActorRole.GM, "title", "gm-package:gm-1",
                List.of(), null, null, null, "", 3600, "key-b", null, null);
        ActionPoolService actions = new ActionPoolService(CLOCK);
        assertThatThrownBy(() -> ActionPackageService.recover(actions, CLOCK, List.of(
                new PackageCommandEvent(PackageCommandEvent.Phase.STARTED, first, null),
                new PackageCommandEvent(PackageCommandEvent.Phase.FAILED, second,
                        "INVALID_STATUS")), record(new ArrayList<>())))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.PACKAGE_RECOVERY_INCOMPLETE));
    }

    @Test
    void commitRecoveryRejectsInconsistentBatchPackageReceiptOrActionIds() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        PlayerAction action = actions.submit(PLAYER_1, "校验行动", "chat", "validate-a1");
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, record(events));
        PackageView created = service.create(GM, "校验包",
                List.of(action.actionId()), 3600, "validate-create");
        service.decide(GM, created.packageId(), Set.of(0), "批准");
        service.commit(GM, created.packageId(), batch -> batch.committed("校验批次"));

        List<PackageCommandEvent> commands = new ArrayList<>(commandEvents(events));
        int appliedIndex = -1;
        for (int index = 0; index < commands.size(); index++) {
            PackageCommandEvent event = commands.get(index);
            if (event.phase() == PackageCommandEvent.Phase.APPLIED
                    && event.command().commandType() == PackageCommand.CommandType.COMMIT) {
                appliedIndex = index;
            }
        }
        assertThat(appliedIndex).isGreaterThanOrEqualTo(0);
        PackageCommand applied = commands.get(appliedIndex).command();
        CommitReceipt receipt = applied.receipt();
        ActionBatch batch = applied.batch();

        List<PackageCommand> invalidCommands = List.of(
                applied.withCommitOutcome(receipt, new ActionBatch(batch.batchId(),
                        "pkg-other", batch.turnId(), batch.actionIds(), batch.texts(),
                        batch.playerIds(), batch.createdAt(), batch.status(), batch.result())),
                applied.withCommitOutcome(new CommitReceipt(receipt.status(), "batch-other",
                        receipt.outcomes(), receipt.reason()), batch),
                applied.withCommitOutcome(receipt, new ActionBatch(batch.batchId(),
                        batch.packageId(), batch.turnId(), List.of("act-other"),
                        List.of("text-other"), List.of("player-x"), batch.createdAt(),
                        batch.status(), batch.result())));
        for (PackageCommand invalid : invalidCommands) {
            List<PackageCommandEvent> tampered = new ArrayList<>(commands);
            tampered.set(appliedIndex, new PackageCommandEvent(
                    PackageCommandEvent.Phase.APPLIED, invalid, null));
            assertThatThrownBy(() -> ActionPackageService.recover(
                    actions, CLOCK, tampered, record(new ArrayList<>())))
                    .isInstanceOfSatisfying(ActionPackageException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    ActionPackageException.PACKAGE_RECOVERY_INCOMPLETE));
        }
    }

    @Test
    void projectionLagCannotBypassCanonicalSingleOwnerRules() {
        FlakyActionPoolStore flaky = new FlakyActionPoolStore();
        ActionPoolService actions = new ActionPoolService(flaky, CLOCK);
        PlayerAction a1 = actions.submit(PLAYER_1, "owner 行动一", "chat", "owner-a1");
        PlayerAction a2 = actions.submit(PLAYER_1, "owner 行动二", "chat", "owner-a2");
        DecisionPackages packages = new DecisionPackages();
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, packages, CLOCK, record(events));

        flaky.failSaves = true;
        PackageView first = service.create(GM, "owner 包",
                List.of(a1.actionId()), 3600, "owner-create-1");
        assertThat(service.actionReconcilePending()).isTrue();
        assertThat(actions.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        int itemCount = first.items().size();

        assertThatThrownBy(() -> service.append(GM, first.packageId(),
                List.of(a1.actionId())))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.CONFLICT));
        assertThat(service.get(first.packageId(), false).orElseThrow().items())
                .hasSize(itemCount);
        assertThatThrownBy(() -> service.create(GM, "另一个 canonical 包",
                List.of(a1.actionId()), 3600, "owner-create-2"))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.CONFLICT));
        assertThat(service.list()).hasSize(1);

        // 直接向 AgentLib 注入第二个 canonical 包占用同一 action，验证 commit owner 校验。
        PackageItem canonicalItem = packages.find(first.packageId()).orElseThrow()
                .items().get(0);
        packages.propose("pkg-intruder", new ProposeRequest("intruder-proposer",
                AccessToken.DEFAULT, List.of(canonicalItem),
                System.currentTimeMillis() + 3_600_000L));
        PackageView decided = service.decide(GM, first.packageId(), Set.of(0), "批准");
        assertThat(decided.state()).isEqualTo(PackageState.DECIDED);
        int eventCount = events.size();
        AtomicInteger executions = new AtomicInteger();
        assertThatThrownBy(() -> service.commit(GM, first.packageId(), batch -> {
            executions.incrementAndGet();
            return batch.committed("不应执行");
        })).isInstanceOfSatisfying(ActionPackageException.class,
                failure -> assertThat(failure.code())
                        .isEqualTo(ActionPackageException.INVALID_STATUS));
        assertThat(executions).hasValue(0);
        assertThat(events).hasSize(eventCount);
        assertThat(service.get(first.packageId(), false).orElseThrow().state())
                .isEqualTo(PackageState.DECIDED);
        assertThat(service.get(first.packageId(), false).orElseThrow().items())
                .hasSize(itemCount);
    }

    @Test
    void reconcileReleasesReplacedAndRemovedOrphansAndProjectsCommittedSubset() {
        FlakyActionPoolStore flaky = new FlakyActionPoolStore();
        ActionPoolService actions = new ActionPoolService(flaky, CLOCK);
        PlayerAction a1 = actions.submit(PLAYER_1, "orphan 一", "chat", "orphan-a1");
        PlayerAction a2 = actions.submit(PLAYER_1, "orphan 二", "chat", "orphan-a2");
        PlayerAction a3 = actions.submit(PLAYER_1, "orphan 三", "chat", "orphan-a3");
        PlayerAction a4 = actions.submit(PLAYER_1, "subset 四", "chat", "orphan-a4");
        PlayerAction a5 = actions.submit(PLAYER_1, "subset 五", "chat", "orphan-a5");
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, record(events));

        PackageView first = service.create(GM, "orphan 包",
                List.of(a1.actionId(), a2.actionId()), 3600, "orphan-create-1");
        assertThat(actions.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        flaky.failSaves = true;
        service.replaceItem(GM, first.packageId(), 0, a3.actionId());
        assertThat(service.actionReconcilePending()).isTrue();
        assertThat(actions.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);
        assertThat(actions.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);

        flaky.failSaves = false;
        service.reconcileActionsFromPackages();
        assertThat(service.actionReconcilePending()).isFalse();
        assertThat(actions.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(actions.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);
        assertThat(actions.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        flaky.failSaves = true;
        service.removeItem(GM, first.packageId(), 0);
        assertThat(service.actionReconcilePending()).isTrue();
        assertThat(actions.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);
        flaky.failSaves = false;
        service.reconcileActionsFromPackages();
        assertThat(service.actionReconcilePending()).isFalse();
        assertThat(actions.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);

        var remainingItem = service.get(first.packageId(), false).orElseThrow().items().get(0);
        service.decide(GM, first.packageId(), Set.of(remainingItem.index()), "只批二");
        service.commit(GM, first.packageId(), batch -> batch.committed("orphan p1"));
        service.reconcileActionsFromPackages();
        assertThat(actions.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);

        PackageView second = service.create(GM, "subset 包",
                List.of(a4.actionId(), a5.actionId()), 3600, "orphan-create-2");
        service.decide(GM, second.packageId(), Set.of(0), "只批四");
        service.commit(GM, second.packageId(), batch -> batch.committed("subset p2"));
        service.reconcileActionsFromPackages();
        assertThat(actions.getForGm(a4.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        assertThat(actions.getForGm(a5.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
    }

    @Test
    void allRejectedCommitProducesOneStableRejectedBatchAcrossReplay() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        PlayerAction a1 = actions.submit(PLAYER_1, "全拒绝一", "chat", "reject-all-a1");
        PlayerAction a2 = actions.submit(PLAYER_1, "全拒绝二", "chat", "reject-all-a2");
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, record(events));
        PackageView created = service.create(GM, "全拒绝包",
                List.of(a1.actionId(), a2.actionId()), 3600, "reject-all-create");
        service.decide(GM, created.packageId(), Set.of(), "全部拒绝");

        AtomicInteger executions = new AtomicInteger();
        CommitReceipt receipt = service.commit(GM, created.packageId(), batch -> {
            executions.incrementAndGet();
            return batch.committed("不应执行");
        });
        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.REJECTED);
        assertThat(receipt.revisionRef()).isNotBlank();
        int executionsAfterInitial = executions.get();
        assertThat(service.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.batchId()).isEqualTo(receipt.revisionRef());
            assertThat(batch.status()).isEqualTo(ActionBatchStatus.REJECTED);
        });
        assertThat(events).filteredOn(event -> event.eventType()
                .equals(DomainEventTypes.BATCH_COMPLETED)).singleElement()
                .satisfies(event -> {
                    ActionBatch batch = (ActionBatch) event.content();
                    assertThat(batch.batchId()).isEqualTo(receipt.revisionRef());
                    assertThat(batch.status()).isEqualTo(ActionBatchStatus.REJECTED);
                    assertThat(event.summary().toString())
                            .doesNotContain("全拒绝一").doesNotContain("全拒绝二");
                });
        int eventCount = events.size();
        CommitReceipt replay = service.commit(GM, created.packageId(), batch -> {
            executions.incrementAndGet();
            return batch.committed("重复");
        });
        assertThat(replay.revisionRef()).isEqualTo(receipt.revisionRef());
        assertThat(replay.revisionRef()).isNotBlank();
        assertThat(events).hasSize(eventCount);
        assertThat(executions).hasValue(executionsAfterInitial);

        List<DomainEvent> recoveryEvents = new ArrayList<>();
        ActionPackageService recovered = ActionPackageService.recover(
                actions, CLOCK, commandEvents(events), record(recoveryEvents));
        assertThat(recovered.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.batchId()).isEqualTo(receipt.revisionRef());
            assertThat(batch.status()).isEqualTo(ActionBatchStatus.REJECTED);
        });
        CommitReceipt recoveredReplay = recovered.commit(
                GM, created.packageId(), batch -> {
                    executions.incrementAndGet();
                    return batch.committed("恢复后重复");
                });
        assertThat(recoveredReplay.revisionRef()).isEqualTo(receipt.revisionRef());
        assertThat(executions).hasValue(executionsAfterInitial);
        assertThat(recovered.batches()).hasSize(1);
        assertThat(recoveryEvents).isEmpty();
    }

    @Test
    void duplicateAppliedCommitEventIsIgnoredAndBatchDeduped() {
        ActionPoolService actions = new ActionPoolService(CLOCK);
        PlayerAction action = actions.submit(PLAYER_1, "重复 APPLIED 行动", "chat",
                "dup-applied-a1");
        List<DomainEvent> events = new ArrayList<>();
        ActionPackageService service = new ActionPackageService(
                actions, new DecisionPackages(), CLOCK, record(events));
        PackageView created = service.create(GM, "重复 APPLIED 包",
                List.of(action.actionId()), 3600, "dup-applied-create");
        service.decide(GM, created.packageId(), Set.of(0), "批准");
        service.commit(GM, created.packageId(), batch -> batch.committed("批次"));
        List<PackageCommandEvent> commands = new ArrayList<>(commandEvents(events));
        int appliedCommitIndex = -1;
        for (int index = 0; index < commands.size(); index++) {
            PackageCommandEvent event = commands.get(index);
            if (event.phase() == PackageCommandEvent.Phase.APPLIED
                    && event.command().commandType() == PackageCommand.CommandType.COMMIT) {
                appliedCommitIndex = index;
            }
        }
        assertThat(appliedCommitIndex).isGreaterThanOrEqualTo(0);
        commands.add(appliedCommitIndex + 1, commands.get(appliedCommitIndex));
        ActionPackageService recovered = ActionPackageService.recover(
                actions, CLOCK, commands, record(new ArrayList<>()));
        assertThat(recovered.batches()).singleElement().satisfies(batch ->
                assertThat(batch.packageId()).isEqualTo(created.packageId()));
        assertThat(recovered.commit(GM, created.packageId(), batch -> {
            throw new AssertionError("重复 APPLIED 恢复后不得重跑 executor");
        }).status()).isEqualTo(CommitReceipt.Status.COMMITTED);
    }

    private static DomainEventJournal record(List<DomainEvent> events) {
        return event -> {
            events.add(event);
            return events.size();
        };
    }

    private static List<PackageCommandEvent> commandEvents(List<DomainEvent> events) {
        return events.stream()
                .filter(event -> event.eventType().startsWith(
                        DomainEventTypes.PACKAGE_COMMAND_PREFIX))
                .map(event -> (PackageCommandEvent) event.content())
                .toList();
    }

    private static final class FlakyActionPoolStore implements ActionPoolStore {
        private final InMemoryActionPoolStore delegate = new InMemoryActionPoolStore();
        private boolean failSaves;

        @Override
        public ActionPoolSnapshot load() {
            return delegate.load();
        }

        @Override
        public void save(ActionPoolSnapshot snapshot) {
            if (failSaves) {
                throw new IllegalStateException("模拟 ActionPool 投影写失败");
            }
            delegate.save(snapshot);
        }
    }
}
