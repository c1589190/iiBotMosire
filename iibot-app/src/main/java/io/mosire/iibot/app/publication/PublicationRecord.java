package io.mosire.iibot.app.publication;

import io.mosire.iibot.taskflow.Hashes;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 一条发布映射。{@link PublicationKind#BATCH} 对应已提交 ActionBatch 的
 * 公共/私密发布；{@link PublicationKind#OPENING} 对应零玩家阶段由 GM 独立创建、
 * 只含公共草稿的开场推文。
 *
 * <p>正文只存在于事件 {@code content_json} 与派生 Markdown 投影中；本记录的
 * {@link #toString()}、日志与 MCP 摘要都不输出正文。{@link #contentHash()} 是给
 * 事件 summary 和记录对账使用的稳定 SHA-256 索引，不是正文存储。</p>
 */
public record PublicationRecord(
        String publicationId,
        String batchId,
        String turnId,
        String publicPostId,
        Map<String, String> privatePostIds,
        String publicNarrative,
        Map<String, String> privateOutcomes,
        Instant createdAt,
        PublicationKind kind) {

    /** 旧 BATCH 记录源码兼容构造器；新代码应显式传 kind。 */
    public PublicationRecord(String publicationId,
                             String batchId,
                             String turnId,
                             String publicPostId,
                             Map<String, String> privatePostIds,
                             String publicNarrative,
                             Map<String, String> privateOutcomes,
                             Instant createdAt) {
        this(publicationId, batchId, turnId, publicPostId, privatePostIds,
                publicNarrative, privateOutcomes, createdAt, PublicationKind.BATCH);
    }

    /** kind-first 源码兼容构造器，便于调用方把发布类型放在最前。 */
    public PublicationRecord(PublicationKind kind,
                             String publicationId,
                             String batchId,
                             String turnId,
                             String publicPostId,
                             Map<String, String> privatePostIds,
                             String publicNarrative,
                             Map<String, String> privateOutcomes,
                             Instant createdAt) {
        this(publicationId, batchId, turnId, publicPostId, privatePostIds,
                publicNarrative, privateOutcomes, createdAt, kind);
    }

    public PublicationRecord {
        requireText(publicationId, "publicationId");
        // 旧 PUBLICATION_GENERATED 事件没有 kind 字段；反序列化后为 null，按 BATCH 兼容。
        kind = kind == null ? PublicationKind.BATCH : kind;

        if (publicPostId != null && publicPostId.isBlank()) {
            throw new IllegalArgumentException("publicPostId 不能为空白");
        }
        privatePostIds = immutableMap(privatePostIds, "privatePostIds");
        publicNarrative = publicNarrative == null ? "" : publicNarrative;
        privateOutcomes = immutableMap(privateOutcomes, "privateOutcomes");
        Objects.requireNonNull(createdAt, "createdAt");
        for (Map.Entry<String, String> entry : privatePostIds.entrySet()) {
            requireText(entry.getKey(), "privatePostIds key");
            requireText(entry.getValue(), "privatePostIds value");
        }
        for (Map.Entry<String, String> entry : privateOutcomes.entrySet()) {
            requireText(entry.getKey(), "privateOutcomes key");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("privateOutcomes value 不能为 null");
            }
        }

        if (kind == PublicationKind.BATCH) {
            requireText(batchId, "batchId");
            if (turnId != null && turnId.isBlank()) {
                throw new IllegalArgumentException("turnId 不能为空白");
            }
            if (publicPostId != null && publicNarrative.isBlank()) {
                throw new IllegalArgumentException("publicPostId 存在时 publicNarrative 不能为空");
            }
            for (String playerId : privatePostIds.keySet()) {
                String outcome = privateOutcomes.get(playerId);
                if (outcome == null || outcome.isBlank()) {
                    throw new IllegalArgumentException(
                            "privatePostId 存在时私密 outcome 不能为空");
                }
            }
        } else {
            // OPENING 是独立公共开场：没有批次、回合或任何私密映射。
            if (batchId != null) {
                throw new IllegalArgumentException("OPENING 记录的 batchId 必须为 null");
            }
            if (turnId != null) {
                throw new IllegalArgumentException("OPENING 记录的 turnId 必须为 null");
            }
            if (!privatePostIds.isEmpty()) {
                throw new IllegalArgumentException("OPENING 记录的 privatePostIds 必须为空");
            }
            if (!privateOutcomes.isEmpty()) {
                throw new IllegalArgumentException("OPENING 记录的 privateOutcomes 必须为空");
            }
            requireText(publicPostId, "publicPostId");
            requireText(publicNarrative, "publicNarrative");
        }
    }

    /** 是否至少包含一个实际发布的草稿映射。 */
    public boolean hasPosts() {
        return publicPostId != null || !privatePostIds.isEmpty();
    }

    /** 稳定内容 hash：只覆盖完整领域字段，不写入正文。 */
    public String contentHash() {
        StringJoiner joiner = new StringJoiner("\u001F");
        joiner.add("kind=" + kind.name());
        joiner.add("publicationId=" + publicationId);
        joiner.add("batchId=" + (batchId == null ? "" : batchId));
        joiner.add("turnId=" + (turnId == null ? "" : turnId));
        joiner.add("publicPostId=" + (publicPostId == null ? "" : publicPostId));
        appendMap(joiner, "privatePostIds", privatePostIds);
        joiner.add("publicNarrative=" + publicNarrative);
        appendMap(joiner, "privateOutcomes", privateOutcomes);
        joiner.add("createdAt=" + createdAt);
        return Hashes.sha256(joiner.toString());
    }

    public String publicNarrativeHash() {
        return Hashes.sha256(publicNarrative);
    }

    public Map<String, String> privateOutcomeHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        privateOutcomes.forEach((playerId, text) -> hashes.put(playerId, Hashes.sha256(text)));
        return Collections.unmodifiableMap(hashes);
    }

    /** 正文不进入默认日志/异常字符串。 */
    @Override
    public String toString() {
        return "PublicationRecord[kind=" + kind
                + ", publicationId=" + publicationId
                + ", batchId=" + batchId
                + ", turnId=" + turnId
                + ", publicPostId=" + publicPostId
                + ", privatePostCount=" + privatePostIds.size()
                + ", publicNarrativeLength=" + publicNarrative.length()
                + ", privateOutcomeCount=" + privateOutcomes.size()
                + ", contentHash=" + contentHash()
                + ']';
    }

    private static void appendMap(StringJoiner joiner, String name, Map<String, String> values) {
        joiner.add(name + ".size=" + values.size());
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(name + "[" + entry.getKey() + "]="
                        + Hashes.sha256(entry.getValue())));
    }

    private static Map<String, String> immutableMap(Map<String, String> input, String name) {
        Objects.requireNonNull(input, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
