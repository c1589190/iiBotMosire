package io.mosire.iibot.bot.weight;

import io.mosire.iibot.bot.chat.ChatType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 权重默认值；可由 Bot 配置覆盖。 */
public record WeightSettings(
        int defaultPrivateSourceWeight,
        int defaultGroupSourceWeight,
        int defaultNotifyThreshold,
        Map<WeightSignal, Integer> signalWeights,
        Map<String, Integer> defaultKeywordWeights,
        Set<String> aliases,
        Set<String> commandPrefixes,
        int maxMessagesPerDigest,
        long quietMillis,
        long cooldownMillis) {

    public WeightSettings {
        checkWeight(defaultPrivateSourceWeight, "defaultPrivateSourceWeight");
        checkWeight(defaultGroupSourceWeight, "defaultGroupSourceWeight");
        checkWeight(defaultNotifyThreshold, "defaultNotifyThreshold");
        signalWeights = normalizeWeights(signalWeights, "signalWeights");
        defaultKeywordWeights = normalizeKeywordWeights(defaultKeywordWeights);
        aliases = normalizeTextSet(aliases, "aliases");
        commandPrefixes = normalizeTextSet(commandPrefixes, "commandPrefixes");
        if (maxMessagesPerDigest < 1 || maxMessagesPerDigest > 1000) {
            throw new IllegalArgumentException("maxMessagesPerDigest 必须在 1..1000");
        }
        if (quietMillis < 0L || quietMillis > 86_400_000L
                || cooldownMillis < 0L || cooldownMillis > 86_400_000L) {
            throw new IllegalArgumentException("quietMillis/cooldownMillis 超出范围");
        }
    }

    public static WeightSettings defaults() {
        return new WeightSettings(
                300,
                0,
                100,
                Map.of(
                        WeightSignal.DM, 200,
                        WeightSignal.MENTION, 200,
                        WeightSignal.REPLY_TO_BOT, 180,
                        WeightSignal.NAME, 120,
                        WeightSignal.COMMAND, 250),
                Map.of(),
                Set.of("贾维洛", "洛洛"),
                Set.of("/", "!"),
                20,
                180_000L,
                60_000L);
    }

    public int defaultSourceWeight(ChatType chatType) {
        return chatType == ChatType.PRIVATE
                ? defaultPrivateSourceWeight : defaultGroupSourceWeight;
    }

    public int weightOf(WeightSignal signal) {
        return signal == null ? 0 : signalWeights.getOrDefault(signal, 0);
    }

    private static Map<WeightSignal, Integer> normalizeWeights(
            Map<WeightSignal, Integer> value, String name) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<WeightSignal, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<WeightSignal, Integer> entry : value.entrySet()) {
            Objects.requireNonNull(entry.getKey(), name + " key");
            checkWeight(entry.getValue(), name + "[" + entry.getKey() + "]");
            normalized.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Integer> normalizeKeywordWeights(Map<String, Integer> value) {
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

    private static Set<String> normalizeTextSet(Set<String> value, String name) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : value) {
            normalized.add(requireText(item, name));
        }
        return Set.copyOf(normalized);
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
