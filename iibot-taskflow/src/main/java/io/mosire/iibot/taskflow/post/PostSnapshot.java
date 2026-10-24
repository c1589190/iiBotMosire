package io.mosire.iibot.taskflow.post;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 推文领域的完整不可变持久化快照。
 *
 * <p>正文可以存在 {@link Post#body()} 中供版本化存储保存，但审计条目只含摘要。快照还保存
 * 每个作者的草稿集合版本与幂等回执，从而支持 store 重建后继续使用同一幂等键。</p>
 */
public record PostSnapshot(
        List<Post> posts,
        Map<String, Long> collectionRevisions,
        Map<String, PostCommandResult> idempotentResults,
        Map<String, String> idempotencyFingerprints,
        Map<String, String> idempotencyRequestMetadata,
        List<PostAuditEntry> auditLog) {

    public PostSnapshot {
        posts = posts == null ? List.of() : List.copyOf(posts);
        collectionRevisions = immutableLinkedMap(collectionRevisions);
        idempotentResults = immutableLinkedMap(idempotentResults);
        // 旧 posts.json / 旧事件没有指纹/元数据字段；Jackson 传 null 时保持可读。
        idempotencyFingerprints = immutableLinkedMap(idempotencyFingerprints);
        idempotencyRequestMetadata = immutableLinkedMap(idempotencyRequestMetadata);
        auditLog = auditLog == null ? List.of() : List.copyOf(auditLog);
        Set<String> ids = new LinkedHashSet<>();
        for (Post post : posts) {
            if (!ids.add(post.postId())) {
                throw new IllegalArgumentException("重复的 postId: " + post.postId());
            }
        }
        for (Map.Entry<String, Long> entry : collectionRevisions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("非法 collectionRevisions 条目");
            }
        }
        for (Map.Entry<String, PostCommandResult> entry : idempotentResults.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("非法 idempotentResults 条目");
            }
        }
        for (Map.Entry<String, String> entry : idempotencyFingerprints.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("非法 idempotencyFingerprints 条目");
            }
        }
        for (Map.Entry<String, String> entry : idempotencyRequestMetadata.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("非法 idempotencyRequestMetadata 条目");
            }
        }
    }

    /** 向后兼容 P6-B3 中间快照；没有 typed request metadata。 */
    public PostSnapshot(List<Post> posts, Map<String, Long> collectionRevisions,
                        Map<String, PostCommandResult> idempotentResults,
                        Map<String, String> idempotencyFingerprints,
                        List<PostAuditEntry> auditLog) {
        this(posts, collectionRevisions, idempotentResults, idempotencyFingerprints,
                Map.of(), auditLog);
    }

    /** 向后兼容 P6-B3 之前的四参数快照；旧数据没有 fingerprint。 */
    public PostSnapshot(List<Post> posts, Map<String, Long> collectionRevisions,
                        Map<String, PostCommandResult> idempotentResults,
                        List<PostAuditEntry> auditLog) {
        this(posts, collectionRevisions, idempotentResults, Map.of(), Map.of(), auditLog);
    }

    public static PostSnapshot empty() {
        return new PostSnapshot(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    public Optional<Post> findPost(String postId) {
        if (postId == null || postId.isBlank()) {
            return Optional.empty();
        }
        return posts.stream().filter(post -> post.postId().equals(postId)).findFirst();
    }

    public int size() {
        return posts.size();
    }

    public long collectionRevision(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return 0L;
        }
        return collectionRevisions.getOrDefault(playerId, 0L);
    }

    /**
     * 创建新快照并替换/追加一篇推文；不修改当前快照。
     */
    public PostSnapshot withPost(Post post) {
        Objects.requireNonNull(post, "post");
        List<Post> next = new ArrayList<>(posts.size() + 1);
        boolean replaced = false;
        for (Post existing : posts) {
            if (existing.postId().equals(post.postId())) {
                next.add(post);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(post);
        }
        return new PostSnapshot(next, collectionRevisions, idempotentResults,
                idempotencyFingerprints, idempotencyRequestMetadata, auditLog);
    }

    public PostSnapshot withCollectionRevision(String playerId, long revision) {
        if (playerId == null || playerId.isBlank() || revision < 0) {
            throw new IllegalArgumentException("非法 collection revision");
        }
        Map<String, Long> next = new LinkedHashMap<>(collectionRevisions);
        next.put(playerId, revision);
        return new PostSnapshot(posts, next, idempotentResults,
                idempotencyFingerprints, idempotencyRequestMetadata, auditLog);
    }

    public PostSnapshot withIdempotentResult(String idempotencyKey, PostCommandResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        Objects.requireNonNull(result, "result");
        Map<String, PostCommandResult> next = new LinkedHashMap<>(idempotentResults);
        next.put(idempotencyKey, result);
        return new PostSnapshot(posts, collectionRevisions, next,
                idempotencyFingerprints, idempotencyRequestMetadata, auditLog);
    }

    public PostSnapshot withIdempotencyFingerprint(String idempotencyKey, String fingerprint) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("idempotencyFingerprint 不能为空");
        }
        Map<String, String> next = new LinkedHashMap<>(idempotencyFingerprints);
        next.put(idempotencyKey, fingerprint);
        return new PostSnapshot(posts, collectionRevisions, idempotentResults, next,
                idempotencyRequestMetadata, auditLog);
    }

    public PostSnapshot withIdempotencyRequestMetadata(String idempotencyKey, String metadata) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        if (metadata == null || metadata.isBlank()) {
            throw new IllegalArgumentException("idempotencyRequestMetadata 不能为空");
        }
        Map<String, String> next = new LinkedHashMap<>(idempotencyRequestMetadata);
        next.put(idempotencyKey, metadata);
        return new PostSnapshot(posts, collectionRevisions, idempotentResults,
                idempotencyFingerprints, next, auditLog);
    }

    public PostSnapshot withAuditEntry(PostAuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        List<PostAuditEntry> next = new ArrayList<>(auditLog.size() + 1);
        next.addAll(auditLog);
        next.add(entry);
        return new PostSnapshot(posts, collectionRevisions, idempotentResults,
                idempotencyFingerprints, idempotencyRequestMetadata, next);
    }

    private static <V> Map<String, V> immutableLinkedMap(Map<String, V> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
