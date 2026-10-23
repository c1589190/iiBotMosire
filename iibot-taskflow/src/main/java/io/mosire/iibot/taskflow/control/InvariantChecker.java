package io.mosire.iibot.taskflow.control;

import io.mosire.iibot.taskflow.Decision;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.TurnSnapshot;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 控制面可安全判定的只读不变量检查。
 *
 * <p>检查对象是 {@link EngineStatus} 的不可变快照，不调用任何写 API、不读取内部字段。
 * 对非法负版本的检查同时覆盖引擎状态与嵌套快照；状态机语义检查覆盖“COMMITTED 必须有
 * 快照”“PUBLISHED 必须有已提交提案”“snapshot/decision/draft 的 turnId 一致”以及
 * “快照世界版本不得领先于当前世界版本”等可证明项。</p>
 */
public final class InvariantChecker {

    private InvariantChecker() {
    }

    /** 单条检查结果。 */
    public record Check(String name, String status, String detail) {
        public Check {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }

        public boolean passed() {
            return "PASS".equals(status);
        }
    }

    /** 汇总结果；只有全部 PASS 时 overall 才是 PASS。 */
    public record Result(String overall, List<Check> checks) {
        public Result {
            Objects.requireNonNull(overall, "overall");
            checks = checks == null ? List.of() : List.copyOf(checks);
        }

        public boolean passed() {
            return "PASS".equals(overall);
        }
    }

    /** 检查一个引擎状态快照；不修改任何状态。 */
    public static Result inspect(EngineStatus status) {
        Objects.requireNonNull(status, "status");
        List<Check> checks = new ArrayList<>();
        checks.add(check("engineRevisionNonNegative", status.engineRevision() >= 0,
                "engineRevision=" + status.engineRevision()));
        checks.add(check("worldVersionNonNegative", status.worldVersion() >= 0,
                "worldVersion=" + status.worldVersion()));
        checks.add(check("countsNonNegative",
                status.playerCount() >= 0 && status.directorCount() >= 0 && status.eventCount() >= 0,
                "players=" + status.playerCount() + ", directors=" + status.directorCount()
                        + ", events=" + status.eventCount()));
        checks.add(check("atMostOneCurrentTurn", true,
                status.turn() == null ? "currentTurnCount=0" : "currentTurnCount=1"));

        TurnView turn = status.turn();
        if (turn == null) {
            checks.add(check("currentTurnSnapshotSafe", true, "没有当前回合"));
            return result(checks);
        }

        checks.add(check("turnRevisionPositive", turn.revision() > 0,
                "turnRevision=" + turn.revision()));
        checks.add(check("turnWorldVersionNonNegative", turn.worldVersion() >= 0,
                "turnWorldVersion=" + turn.worldVersion()));
        checks.add(check("turnDraftTurnIdsConsistent", turn.drafts().stream()
                        .allMatch(draft -> same(draft.turnId(), turn.turnId())),
                "drafts=" + turn.drafts().size()));
        checks.add(check("turnDecisionTurnIdsConsistent", turn.decisions().stream()
                        .allMatch(decision -> same(decision.turnId(), turn.turnId())),
                "decisions=" + turn.decisions().size()));

        TurnSnapshot snapshot = turn.snapshot();
        boolean needsSnapshot = turn.state() == TurnState.COMMITTED
                || turn.state() == TurnState.PUBLISHED;
        checks.add(check("committedHasSnapshot", !needsSnapshot || snapshot != null,
                "state=" + turn.state()));
        if (snapshot != null) {
            checks.add(check("snapshotTurnIdConsistent", same(snapshot.turnId(), turn.turnId()),
                    "snapshotTurnId=" + snapshot.turnId()));
            checks.add(check("snapshotWorldVersionNotFuture",
                    snapshot.worldVersion() >= 0 && snapshot.worldVersion() <= status.worldVersion(),
                    "snapshotWorldVersion=" + snapshot.worldVersion()
                            + ", currentWorldVersion=" + status.worldVersion()));
            checks.add(check("snapshotDecisionsTurnIdsConsistent", snapshot.decisions().stream()
                            .allMatch(decision -> same(decision.turnId(), turn.turnId())),
                    "snapshotDecisions=" + snapshot.decisions().size()));
            checks.add(check("snapshotHashPresent",
                    snapshot.contentHash() != null && !snapshot.contentHash().isBlank(),
                    "snapshotHashPresent=" + (snapshot.contentHash() != null)));
        }

        boolean needsProposal = turn.state() == TurnState.COMMITTED
                || turn.state() == TurnState.PUBLISHED;
        checks.add(check("committedHasProposal", !needsProposal || turn.committedProposal() != null,
                "state=" + turn.state()));
        if (turn.committedProposal() != null) {
            checks.add(check("committedProposalTurnIdConsistent",
                    same(turn.committedProposal().turnId(), turn.turnId()),
                    "proposalTurnId=" + turn.committedProposal().turnId()));
            checks.add(check("committedProposalWorldVersionNotFuture",
                    turn.committedProposal().expectedWorldVersion() <= status.worldVersion(),
                    "proposalWorldVersion=" + turn.committedProposal().expectedWorldVersion()
                            + ", currentWorldVersion=" + status.worldVersion()));
            checks.add(check("committedProposalHashPresent",
                    turn.committedProposal().proposalHash() != null
                            && !turn.committedProposal().proposalHash().isBlank(),
                    "proposalHashPresent=" + (turn.committedProposal().proposalHash() != null)));
        }

        // 对已提交/已发布回合，当前世界版本至少不能低于快照版本；更精确的 +1 关系会被
        // 回合提交后的合法世界装载改变，故只做能安全证明的单向检查。
        if (snapshot != null) {
            checks.add(check("publishedWorldVersionNotBeforeSnapshot",
                    !needsSnapshot || status.worldVersion() >= snapshot.worldVersion(),
                    "state=" + turn.state() + ", worldVersion=" + status.worldVersion()
                            + ", snapshotWorldVersion=" + snapshot.worldVersion()));
        }
        return result(checks);
    }

    private static Check check(String name, boolean passed, String detail) {
        return new Check(name, passed ? "PASS" : "FAIL", detail);
    }

    private static Result result(List<Check> checks) {
        boolean allPassed = checks.stream().allMatch(Check::passed);
        return new Result(allPassed ? "PASS" : "FAIL", checks);
    }

    private static boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    /** 为测试与审计提供安全的“是否有重复玩家决策”检查，不接触引擎内部。 */
    public static boolean hasDuplicateDecisionPlayers(TurnView turn) {
        Objects.requireNonNull(turn, "turn");
        return turn.decisions().stream().map(Decision::playerId).distinct().count()
                != turn.decisions().size();
    }

    /** 为测试与审计提供安全的“草稿是否含空 ID”检查，不接触引擎内部。 */
    public static boolean hasBlankDraftId(TurnView turn) {
        Objects.requireNonNull(turn, "turn");
        for (DecisionDraft draft : turn.drafts()) {
            if (draft.draftId() == null || draft.draftId().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
