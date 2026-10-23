package io.mosire.iibot.taskflow.post;

import java.time.Instant;
import java.util.Objects;

/**
 * 推文状态变更的审计摘要。
 *
 * <p>本记录刻意只保存动作、状态、版本与必要摘要，不包含推文正文或标题。拒绝原因通过
 * {@code summary} 保存，调用方不得把正文伪装成原因写入这里。</p>
 */
public record PostAuditEntry(
        PostAuditAction action,
        String postId,
        String actorId,
        PostStatus fromStatus,
        PostStatus toStatus,
        long postRevision,
        String summary,
        Instant at) {

    public PostAuditEntry {
        Objects.requireNonNull(action, "action");
        if (postId == null || postId.isBlank()) {
            throw new IllegalArgumentException("postId 不能为空");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        if (postRevision < 1) {
            throw new IllegalArgumentException("postRevision 必须为正");
        }
        summary = summary == null ? "" : summary;
        Objects.requireNonNull(at, "at");
    }
}
