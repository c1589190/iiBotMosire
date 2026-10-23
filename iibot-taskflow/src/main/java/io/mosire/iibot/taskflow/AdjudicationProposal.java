package io.mosire.iibot.taskflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中央 GM Agent 提交的结构化推演提案。
 *
 * <p>提案不是世界变更本身。宿主必须校验 schemaVersion、expectedWorldVersion、玩家
 * 集合、worldDelta 白名单与大小，再用单个 committed 事件原子生效。proposalHash
 * 用于防止提案在传输/审查后被改动。</p>
 */
public record AdjudicationProposal(
        int schemaVersion,
        String turnId,
        long expectedWorldVersion,
        String publicNarrative,
        Map<String, String> playerOutcomes,
        List<WorldDelta> worldDeltas,
        List<String> nextHooks,
        String proposalHash) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AdjudicationProposal {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (expectedWorldVersion < 0) {
            throw new IllegalArgumentException("expectedWorldVersion 不能为负");
        }
        publicNarrative = publicNarrative == null ? "" : publicNarrative;
        Objects.requireNonNull(playerOutcomes, "playerOutcomes");
        playerOutcomes = Map.copyOf(new LinkedHashMap<>(playerOutcomes));
        Objects.requireNonNull(worldDeltas, "worldDeltas");
        worldDeltas = List.copyOf(worldDeltas);
        Objects.requireNonNull(nextHooks, "nextHooks");
        nextHooks = List.copyOf(nextHooks);
        if (proposalHash == null || proposalHash.isBlank()) {
            throw new IllegalArgumentException("proposalHash 不能为空");
        }
    }

    /** 创建并自动计算 proposalHash 的便捷工厂。 */
    public static AdjudicationProposal create(int schemaVersion, String turnId, long expectedWorldVersion,
                                              String publicNarrative, Map<String, String> playerOutcomes,
                                              List<WorldDelta> worldDeltas, List<String> nextHooks) {
        Map<String, String> outcomes = playerOutcomes == null ? Map.of() : Map.copyOf(playerOutcomes);
        List<WorldDelta> deltas = worldDeltas == null ? List.of() : List.copyOf(worldDeltas);
        List<String> hooks = nextHooks == null ? List.of() : List.copyOf(nextHooks);
        String hash = Hashes.sha256(canonicalText(schemaVersion, turnId, expectedWorldVersion,
                publicNarrative, outcomes, deltas, hooks));
        return new AdjudicationProposal(schemaVersion, turnId, expectedWorldVersion,
                publicNarrative, outcomes, deltas, hooks, hash);
    }

    /** 不含 proposalHash 的稳定文本。 */
    public static String canonicalText(int schemaVersion, String turnId, long expectedWorldVersion,
                                       String publicNarrative, Map<String, String> playerOutcomes,
                                       List<WorldDelta> worldDeltas, List<String> nextHooks) {
        String narrative = publicNarrative == null ? "" : publicNarrative;
        String outcomes = playerOutcomes == null ? "" : playerOutcomes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\u001e" + b).orElse("");
        String deltas = worldDeltas == null ? "" : worldDeltas.stream()
                .map(WorldDelta::canonicalText)
                .reduce((a, b) -> a + "\u001f" + b).orElse("");
        String hooks = nextHooks == null ? "" : String.join("\u001f", nextHooks);
        return "schema=" + schemaVersion
                + "|turn=" + turnId
                + "|world=" + expectedWorldVersion
                + "|narrative=" + narrative
                + "|outcomes=" + outcomes
                + "|deltas=" + deltas
                + "|hooks=" + hooks;
    }

    /** 重新计算并比对 hash，防止提案内容被非原子修改。 */
    public boolean hasValidHash() {
        return proposalHash.equals(Hashes.sha256(canonicalText(schemaVersion, turnId,
                expectedWorldVersion, publicNarrative, playerOutcomes, worldDeltas, nextHooks)));
    }
}
