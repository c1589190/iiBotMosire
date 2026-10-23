package io.mosire.iibot.taskflow.post;

/**
 * 推文状态机。
 *
 * <pre>
 * PENDING_APPROVAL → APPROVED → PUBLISHED
 *                └→ REJECTED
 * </pre>
 *
 * <p>{@link #DRAFT} 保留给后续“先起草再显式提交”的扩展；当前 {@code PostService}
 * 创建草稿后直接进入 {@link #PENDING_APPROVAL}。</p>
 */
public enum PostStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    PUBLISHED
}
