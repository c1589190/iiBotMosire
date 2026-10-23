package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 回合的只读视图。
 *
 * <p>所有集合和快照均为不可变副本；调用方修改视图不会影响引擎内部状态。turnState
 * 与 revision 用于把后续写命令放到同一个状态机上。</p>
 */
public record TurnView(
        String turnId,
        int turnNo,
        TurnState state,
        long revision,
        long worldVersion,
        String promptVersion,
        Instant opensAt,
        Instant closesAt,
        Instant lockedAt,
        List<DecisionDraft> drafts,
        List<Decision> decisions,
        TurnSnapshot snapshot,
        AdjudicationProposal committedProposal) {

    public TurnView {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    /** 当前有效决策（按 playerId 稳定排序）。 */
    public Map<String, Decision> decisionsByPlayer() {
        Map<String, Decision> result = new LinkedHashMap<>();
        decisions.stream().sorted((a, b) -> a.playerId().compareTo(b.playerId()))
                .forEach(d -> result.put(d.playerId(), d));
        return Map.copyOf(result);
    }
}
