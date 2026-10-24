package io.mosire.iibot.app.records;

/**
 * 记录查询的单条摘要。
 *
 * <p>只含逻辑 ID、文件相对名与 hash；不包含正文，也永远不包含物理路径。
 * {@code opening} 摘要没有回合，因此 {@code turnId} 为 null，不是伪造的 turn。</p>
 */
public record RecordSummary(
        String kind,
        String turnId,
        String playerId,
        String actionId,
        String packageId,
        String batchId,
        String postId,
        String file,
        String contentHash,
        String summary,
        String publicationId) {

    /** 旧调用方源码兼容构造器；开盘记录才会显式传 publicationId。 */
    public RecordSummary(String kind,
                         String turnId,
                         String playerId,
                         String actionId,
                         String packageId,
                         String batchId,
                         String postId,
                         String file,
                         String contentHash,
                         String summary) {
        this(kind, turnId, playerId, actionId, packageId, batchId, postId,
                file, contentHash, summary, null);
    }

    public RecordSummary {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (turnId == null || turnId.isBlank()) {
            if (!"opening".equals(kind)) {
                throw new IllegalArgumentException("非 opening 摘要的 turnId 不能为空");
            }
        }
        if ("opening".equals(kind)
                && (publicationId == null || publicationId.isBlank())) {
            throw new IllegalArgumentException("opening 摘要的 publicationId 不能为空");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        contentHash = contentHash == null ? "" : contentHash;
        summary = summary == null ? "" : summary;
    }

    @Override
    public String toString() {
        return "RecordSummary[kind=" + kind
                + ", turnId=" + turnId
                + ", playerId=" + playerId
                + ", actionId=" + actionId
                + ", packageId=" + packageId
                + ", batchId=" + batchId
                + ", postId=" + postId
                + ", publicationId=" + publicationId
                + ", file=" + file
                + ", contentHash=" + contentHash
                + ']';
    }
}
