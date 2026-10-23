package io.mosire.iibot.taskflow.snapshot;

import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.Decision;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.TurnSnapshot;
import io.mosire.iibot.taskflow.TurnState;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 单个回合（含草稿、决策、冻结快照和已提交提案）的完整纯数据快照。 */
public record TurnStateSnapshot(
        String turnId,
        int turnNo,
        TurnState state,
        long revision,
        Instant opensAt,
        Instant closesAt,
        long openedWorldVersion,
        Instant lockedAt,
        Map<String, DecisionDraft> drafts,
        Map<String, Decision> decisionsByPlayer,
        TurnSnapshot snapshot,
        AdjudicationProposal committedProposal) {

    public TurnStateSnapshot {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (turnNo <= 0) {
            throw new IllegalArgumentException("turnNo 必须为正");
        }
        Objects.requireNonNull(state, "state");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须为正");
        }
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        if (openedWorldVersion < 0) {
            throw new IllegalArgumentException("openedWorldVersion 不能为负");
        }
        drafts = immutableMap(drafts, "drafts");
        decisionsByPlayer = immutableMap(decisionsByPlayer, "decisionsByPlayer");
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values, String name) {
        Objects.requireNonNull(values, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
