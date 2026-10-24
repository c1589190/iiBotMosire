package io.mosire.iibot.taskflow.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.PlayerAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionPackageServiceTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final Actor P1 = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor P2 = new Actor("player-2", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void createSelectsActionsAndSummaryHidesArgsUntilFullRead() {
        Fixture f = new Fixture();
        PlayerAction a1 = f.submit(P1, "探索北门", "chat", "a1");
        PlayerAction a2 = f.submit(P1, "同时准备撤退路线", "chat", "a2");

        PackageView created = f.service.create(GM, "北门行动", List.of(a1.actionId(),
                a2.actionId()), 3600, "create-1");

        assertThat(created.packageId()).startsWith("pkg-");
        assertThat(created.title()).isEqualTo("北门行动");
        assertThat(created.state()).isEqualTo(PackageState.PROPOSED);
        assertThat(created.items()).hasSize(2);
        assertThat(f.pool.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);
        assertThat(f.pool.getForGm(a2.actionId()).orElseThrow().packageId())
                .isEqualTo(created.packageId());

        PackageView summary = f.service.get(created.packageId(), false).orElseThrow();
        assertThat(summary.items()).allSatisfy(item -> assertThat(item.args()).isNull());
        assertThat(summary.toString()).doesNotContain("探索北门")
                .doesNotContain("撤退路线");
        assertThat(summary.items().get(0).summary()).doesNotContain("探索北门");

        PackageView full = f.service.get(created.packageId(), true).orElseThrow();
        assertThat(full.items().get(0).args()).containsEntry("actionId", a1.actionId())
                .containsEntry("playerId", "player-1")
                .containsEntry("text", "探索北门")
                .containsEntry("source", "chat")
                .containsKey("submittedAt");
        assertThat(full.items().get(0).argsDigest()).hasSize(12);
        assertThat(full.toString()).doesNotContain("探索北门");
    }

    @Test
    void appendReplaceRemoveOnlyWhileProposedAndFrozenAfterFirstItemDecision()
            throws Exception {
        Fixture f = new Fixture();
        PlayerAction a1 = f.submit(P1, "行动一", "chat", "1");
        PlayerAction a2 = f.submit(P2, "行动二", "chat", "2");
        PlayerAction a3 = f.submit(P1, "行动三", "chat", "3");
        PlayerAction a4 = f.submit(P1, "行动四", "chat", "4");
        PlayerAction a5 = f.submit(P1, "行动五", "chat", "5");
        PlayerAction a6 = f.submit(P1, "行动六", "chat", "6");
        PackageView created = f.service.create(GM, "混合包", List.of(a1.actionId()), 3600, "c1");

        PackageView appended = f.service.append(GM, created.packageId(),
                List.of(a2.actionId()));
        assertThat(appended.items()).extracting(item -> item.index()).containsExactly(0, 1);
        assertThat(f.pool.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        PackageView replaced = f.service.replaceItem(GM, created.packageId(), 0,
                a3.actionId());
        assertThat(replaced.items().get(0).argsDigest()).isNotBlank();
        assertThat(f.pool.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(f.pool.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        PackageView removed = f.service.removeItem(GM, created.packageId(), 1);
        assertThat(removed.items()).extracting(item -> item.index()).containsExactly(0);
        assertThat(f.pool.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);

        f.service.append(GM, created.packageId(), List.of(a4.actionId()));
        PackageView deciding = f.service.decideItem(GM, created.packageId(), 0, true, "先批一条");
        assertThat(deciding.state()).isEqualTo(PackageState.DECIDING);
        int itemCountBeforeFrozen = deciding.items().size();

        assertThatThrownBy(() -> f.service.append(GM, created.packageId(),
                List.of(a5.actionId())))
                .isInstanceOfSatisfying(DecisionPackages.RejectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DecisionPackages.PACKAGE_CONTENT_FROZEN));
        assertThatThrownBy(() -> f.service.replaceItem(GM, created.packageId(), 0,
                a6.actionId()))
                .isInstanceOfSatisfying(DecisionPackages.RejectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DecisionPackages.PACKAGE_CONTENT_FROZEN));
        assertThatThrownBy(() -> f.service.removeItem(GM, created.packageId(), 1))
                .isInstanceOfSatisfying(DecisionPackages.RejectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DecisionPackages.PACKAGE_CONTENT_FROZEN));

        assertThat(f.service.get(created.packageId(), false).orElseThrow().items())
                .hasSize(itemCountBeforeFrozen);
        assertThat(f.pool.getForGm(a5.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(f.pool.getForGm(a6.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(f.pool.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);
        assertThat(f.pool.getForGm(a4.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);
    }

    @Test
    void onlyGmSideRolesCanManagePackages() {
        Fixture f = new Fixture();
        PlayerAction a1 = f.submit(P1, "行动", "chat", "1");

        assertThatThrownBy(() -> f.service.create(P1, "包", List.of(a1.actionId()),
                3600, "c"))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.FORBIDDEN));
        PackageView created = f.service.create(GM, "包", List.of(a1.actionId()), 3600, "c");
        assertThatThrownBy(() -> f.service.decide(P1, created.packageId(), Set.of(0), "x"))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.FORBIDDEN));
        assertThatThrownBy(() -> f.service.commit(P1, created.packageId(),
                batch -> batch.committed("no")))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPackageException.FORBIDDEN));
        assertThat(f.service.batches()).isEmpty();
    }

    @Test
    void decideCommitExecutesOnlyApprovedSubsetExactlyOnceAndUpdatesActions() {
        Fixture f = new Fixture();
        PlayerAction a1 = f.submit(P1, "第一条正文", "chat", "1");
        PlayerAction a2 = f.submit(P2, "第二条正文", "chat", "2");
        PlayerAction a3 = f.submit(P1, "第三条正文", "chat", "3");
        PackageView created = f.service.create(GM, "裁决包", List.of(a1.actionId(),
                a2.actionId(), a3.actionId()), 3600, "create");

        PackageView decided = f.service.decide(GM, created.packageId(), Set.of(0, 2),
                "批准一三");
        assertThat(decided.state()).isEqualTo(PackageState.DECIDED);
        assertThat(decided.decision()).isNotNull();
        assertThat(decided.decision().approvedIndexes()).containsExactlyInAnyOrder(0, 2);

        AtomicInteger executions = new AtomicInteger();
        List<ActionBatch> seen = new ArrayList<>();
        ActionBatchExecutor executor = batch -> {
            executions.incrementAndGet();
            seen.add(batch);
            return batch.committed("batch-recorded");
        };

        CommitReceipt receipt = f.service.commit(GM, created.packageId(), executor);
        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        assertThat(receipt.revisionRef()).isNotBlank();
        assertThat(executions).hasValue(1);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).actionIds())
                .containsExactly(a1.actionId(), a3.actionId());
        assertThat(seen.get(0).texts()).containsExactly("第一条正文", "第三条正文");
        assertThat(seen.get(0).playerIds()).containsExactly("player-1", "player-1");
        assertThat(seen.get(0).packageId()).isEqualTo(created.packageId());
        assertThat(seen.get(0).turnId()).isNull();

        assertThat(f.pool.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        assertThat(f.pool.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(f.pool.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        assertThat(f.service.get(created.packageId(), false).orElseThrow().state())
                .isEqualTo(PackageState.COMMITTED);
        assertThat(f.service.batches()).hasSize(1);

        CommitReceipt replay = f.service.commit(GM, created.packageId(), executor);
        assertThat(replay.revisionRef()).isEqualTo(receipt.revisionRef());
        assertThat(executions).hasValue(1);
        assertThat(f.service.batches()).hasSize(1);
    }

    @Test
    void itemDecisionsFreezeContentAndCommitSkipsUnstatedItems() {
        Fixture f = new Fixture();
        PlayerAction approved = f.submit(P1, "批准的行动", "chat", "1");
        PlayerAction unstated = f.submit(P1, "未表态的行动", "chat", "2");
        PackageView created = f.service.create(GM, "逐条包", List.of(approved.actionId(),
                unstated.actionId()), 3600, "create");

        PackageView deciding = f.service.decideItem(GM, created.packageId(), 0, true, "ok");
        assertThat(deciding.state()).isEqualTo(PackageState.DECIDING);

        List<ActionBatch> seen = new ArrayList<>();
        CommitReceipt receipt = f.service.commit(GM, created.packageId(), batch -> {
            seen.add(batch);
            return batch.committed("done");
        });

        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).actionIds()).containsExactly(approved.actionId());
        assertThat(seen.get(0).texts()).containsExactly("批准的行动");
        assertThat(f.pool.getForGm(approved.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        assertThat(f.pool.getForGm(unstated.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
    }

    @Test
    void executorFailureMeansNoActionCommitted() {
        Fixture f = new Fixture();
        PlayerAction a1 = f.submit(P1, "正文", "chat", "1");
        PackageView created = f.service.create(GM, "失败包", List.of(a1.actionId()), 3600, "c");
        f.service.decide(GM, created.packageId(), Set.of(0), "approve");

        CommitReceipt receipt = f.service.commit(GM, created.packageId(), batch -> {
            throw new IllegalStateException("offline");
        });

        assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.REJECTED);
        assertThat(f.pool.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertThat(f.service.get(created.packageId(), false).orElseThrow().state())
                .isEqualTo(PackageState.REJECTED);
    }

    private static final class Fixture {
        private final ActionPoolService pool = new ActionPoolService(CLOCK);
        private final ActionPackageService service = new ActionPackageService(pool,
                new DecisionPackages(), CLOCK);

        private PlayerAction submit(Actor player, String text, String source, String key) {
            return pool.submit(player, text, source, key);
        }
    }
}
