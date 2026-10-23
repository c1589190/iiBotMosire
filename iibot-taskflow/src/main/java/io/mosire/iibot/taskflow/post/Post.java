package io.mosire.iibot.taskflow.post;

import java.time.Instant;
import java.util.Objects;

/**
 * 一篇待审核或已发布推文的不可变快照。
 *
 * <p>{@code authorPlayerId} 只能由 {@link PostService} 从宿主已认证的 PLAYER actor 写入。
 * {@code body} 属于版本化正文存储，不进入事件 payload、审计摘要或日志；本记录的
 * {@link #toString()} 也刻意省略正文。</p>
 *
 * @param deliveryState 已发布后由 outbox 记录的外部送达状态；未发布时为 {@code null}
 * @param revision      单篇推文的聚合版本，从 1 开始，每次状态变更递增
 */
public record Post(
        String postId,
        String authorPlayerId,
        PostVisibility visibility,
        String title,
        String body,
        PostStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        String approvedBy,
        String publishedBy,
        DeliveryState deliveryState) {

    public Post {
        if (postId == null || postId.isBlank()) {
            throw new IllegalArgumentException("postId 不能为空");
        }
        if (authorPlayerId == null || authorPlayerId.isBlank()) {
            throw new IllegalArgumentException("authorPlayerId 不能为空");
        }
        Objects.requireNonNull(visibility, "visibility");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        Objects.requireNonNull(status, "status");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须为正");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (approvedBy != null && approvedBy.isBlank()) {
            throw new IllegalArgumentException("approvedBy 不能为空白");
        }
        if (publishedBy != null && publishedBy.isBlank()) {
            throw new IllegalArgumentException("publishedBy 不能为空白");
        }
    }

    /** 兼容只关心核心字段的构造；未发布推文没有送达状态。 */
    public Post(String postId, String authorPlayerId, PostVisibility visibility,
                String title, String body, PostStatus status, long revision,
                Instant createdAt, Instant updatedAt, String approvedBy, String publishedBy) {
        this(postId, authorPlayerId, visibility, title, body, status, revision,
                createdAt, updatedAt, approvedBy, publishedBy, null);
    }

    /** 正文不进入默认日志/异常字符串。 */
    @Override
    public String toString() {
        return "Post[postId=" + postId
                + ", authorPlayerId=" + authorPlayerId
                + ", visibility=" + visibility
                + ", status=" + status
                + ", revision=" + revision
                + ", deliveryState=" + deliveryState + "]";
    }
}
