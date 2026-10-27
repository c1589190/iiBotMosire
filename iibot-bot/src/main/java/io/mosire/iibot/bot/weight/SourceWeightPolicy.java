package io.mosire.iibot.bot.weight;

import io.mosire.iibot.bot.chat.ChatType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一个 thread/来源的权重策略。 */
public record SourceWeightPolicy(
        String threadId,
        ChatType chatType,
        String targetId,
        int sourceWeight,
        int notifyThreshold,
        Map<WeightSignal, Integer> signalWeights,
        Map<String, Integer> keywordWeights,
        int maxMessages,
        long quietMillis,
        long cooldownMillis,
        boolean bootstrapDelivered,
        long revision,
        Instant updatedAt) {

    public SourceWeightPolicy {
        threadId = requireText(threadId, "threadId");
        chatType = Objects.requireNonNull(chatType, "chatType");
        targetId = requireText(targetId, "targetId");
        checkWeight(sourceWeight, "sourceWeight");
        checkWeight(notifyThreshold, "notifyThreshold");
        signalWeights = normalizeSignals(signalWeights);
        keywordWeights = normalizeKeywords(keywordWeights);
        if (maxMessages < 1 || maxMessages > 1000) {
            throw new IllegalArgumentException("maxMessages 必须在 1..1000");
        }
        if (quietMillis < 0L || quietMillis > 86_400_000L
                || cooldownMillis < 0L || cooldownMillis > 86_400_000L) {
            throw new IllegalArgumentException("quietMillis/cooldownMillis 超出范围");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("revision 不能为负");
        }
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    public SourceWeightPolicy withBootstrapDelivered(boolean delivered, Instant at) {
        return new SourceWeightPolicy(threadId, chatType, targetId, sourceWeight,
                notifyThreshold, signalWeights, keywordWeights, maxMessages,
                quietMillis, cooldownMillis, delivered, revision, at);
    }

    public SourceWeightPolicy withRevision(long newRevision, Instant at) {
        return new SourceWeightPolicy(threadId, chatType, targetId, sourceWeight,
                notifyThreshold, signalWeights, keywordWeights, maxMessages,
                quietMillis, cooldownMillis, bootstrapDelivered, newRevision, at);
    }

    private static Map<WeightSignal, Integer> normalizeSignals(
            Map<WeightSignal, Integer> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<WeightSignal, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<WeightSignal, Integer> entry : value.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "signal");
            checkWeight(entry.getValue(), "signalWeights[" + entry.getKey() + "]");
            normalized.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Integer> normalizeKeywords(Map<String, Integer> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : value.entrySet()) {
            String key = requireText(entry.getKey(), "keyword");
            checkWeight(entry.getValue(), "keywordWeights[" + key + "]");
            normalized.put(key, entry.getValue());
        }
        return Map.copyOf(normalized);
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
