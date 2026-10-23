package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 回合冻结后的不可变决策集合。
 *
 * <p>lock 成功后快照与 hash 固定；推演只能读取该快照，不能通过 late draft 或确认
 * 命令改变它。contentHash 给下游提案提供稳定的输入指纹。</p>
 */
public record TurnSnapshot(
        String turnId,
        int turnNo,
        long worldVersion,
        String promptVersion,
        List<Decision> decisions,
        Instant confirmedAt,
        String contentHash) {

    public TurnSnapshot {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (turnNo <= 0 || worldVersion < 0) {
            throw new IllegalArgumentException("turnNo 必须为正且 worldVersion 不能为负");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion 不能为空");
        }
        Objects.requireNonNull(decisions, "decisions");
        decisions = List.copyOf(decisions);
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash 不能为空");
        }
    }

    /** 快照 hash 的规范输入，不含 contentHash 自身。 */
    public static String canonicalText(String turnId, int turnNo, long worldVersion,
                                       String promptVersion, List<Decision> decisions,
                                       Instant confirmedAt) {
        StringBuilder builder = new StringBuilder()
                .append("turnId=").append(turnId)
                .append("|turnNo=").append(turnNo)
                .append("|worldVersion=").append(worldVersion)
                .append("|promptVersion=").append(promptVersion)
                .append("|confirmedAt=").append(confirmedAt);
        for (Decision decision : decisions) {
            builder.append("|decision=").append(decision.decisionId())
                    .append(':').append(decision.playerId())
                    .append(':').append(decision.contentHash())
                    .append(':').append(canonicalFields(decision.fields()));
        }
        return builder.toString();
    }

    private static String canonicalFields(Map<String, Object> fields) {
        Map<String, Object> ordered = new LinkedHashMap<>(fields);
        return ordered.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\u001e" + b).orElse("");
    }
}
