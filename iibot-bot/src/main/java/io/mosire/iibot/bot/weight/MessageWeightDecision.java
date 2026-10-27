package io.mosire.iibot.bot.weight;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 单条消息的权重判定；不包含正文。 */
public record MessageWeightDecision(
        String messageKey,
        String threadId,
        int sourceWeight,
        int signalWeight,
        int keywordWeight,
        int totalScore,
        WeightWakeReason wakeReason,
        List<WeightSignal> matchedSignals,
        List<String> matchedKeywords,
        boolean actionable,
        boolean bootstrap,
        long policyRevision,
        Instant decidedAt) {

    public MessageWeightDecision {
        messageKey = requireText(messageKey, "messageKey");
        threadId = requireText(threadId, "threadId");
        checkWeight(sourceWeight, "sourceWeight");
        checkWeight(signalWeight, "signalWeight");
        checkWeight(keywordWeight, "keywordWeight");
        checkWeight(totalScore, "totalScore");
        wakeReason = Objects.requireNonNull(wakeReason, "wakeReason");
        matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        if (policyRevision < 0L) {
            throw new IllegalArgumentException("policyRevision 不能为负");
        }
        decidedAt = decidedAt == null ? Instant.EPOCH : decidedAt;
    }

    private static void checkWeight(int value, String name) {
        if (value < -1000 || value > 1000) {
            throw new IllegalArgumentException(name + " 必须在 -1000..1000");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
