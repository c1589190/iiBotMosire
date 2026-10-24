package io.mosire.iibot.app.decision;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 中央推演批次的严格结构化输出。
 *
 * <p>模型只能返回 JSON；本类型不接受 Markdown code fence、未知字段或推理过程。批次持久化
 * 的是该结构而非模型原始 reasoning。</p>
 */
public record AdjudicationOutput(
        String publicSummary,
        Map<String, String> privateOutcomes,
        boolean worldChanged,
        String noOpReason) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public AdjudicationOutput {
        publicSummary = publicSummary == null ? "" : publicSummary.strip();
        privateOutcomes = privateOutcomes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(privateOutcomes));
        noOpReason = noOpReason == null ? "" : noOpReason.strip();
        for (Map.Entry<String, String> entry : privateOutcomes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("privateOutcomes 的键值不能为空");
            }
        }
    }

    public static AdjudicationOutput parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("推演输出为空");
        }
        String trimmed = rawJson.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("推演输出必须是单个 JSON object");
        }
        try {
            AdjudicationOutput output = JSON.readValue(trimmed, AdjudicationOutput.class);
            if (output == null) {
                throw new IllegalArgumentException("推演输出 JSON 为空");
            }
            return output;
        } catch (JsonProcessingException | RuntimeException invalid) {
            throw new IllegalArgumentException("推演输出 JSON 无法严格解析", invalid);
        }
    }

    /** 解析失败时返回 empty，用于发布编排兼容旧批次文本。 */
    public static java.util.Optional<AdjudicationOutput> tryParse(String rawJson) {
        try {
            return java.util.Optional.of(parse(rawJson));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    @JsonIgnore
    public boolean isNoOp() {
        return !worldChanged || !noOpReason.isBlank();
    }

    public void validatePlayerScope(Set<String> allowedPlayerIds) {
        Objects.requireNonNull(allowedPlayerIds, "allowedPlayerIds");
        for (String playerId : privateOutcomes.keySet()) {
            if (!allowedPlayerIds.contains(playerId)) {
                throw new IllegalArgumentException("privateOutcomes 含批次外 playerId");
            }
        }
        if (worldChanged && publicSummary.isBlank() && privateOutcomes.isEmpty()) {
            throw new IllegalArgumentException("worldChanged=true 但没有任何可读结果");
        }
        if (!worldChanged && noOpReason.isBlank()) {
            throw new IllegalArgumentException("worldChanged=false 时必须给出 noOpReason");
        }
    }

    public String canonicalJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("无法序列化 AdjudicationOutput", impossible);
        }
    }
}
