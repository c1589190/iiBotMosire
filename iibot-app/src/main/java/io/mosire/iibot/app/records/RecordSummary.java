package io.mosire.iibot.app.records;

/**
 * 记录查询的单条摘要。
 *
 * <p>只含逻辑 ID、文件相对名与 hash；不包含正文，也永远不包含物理路径。</p>
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
        String summary) {

    public RecordSummary {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
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
                + ", file=" + file
                + ", contentHash=" + contentHash
                + ']';
    }
}
