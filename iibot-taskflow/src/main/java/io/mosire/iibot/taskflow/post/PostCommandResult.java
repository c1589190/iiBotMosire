package io.mosire.iibot.taskflow.post;

import java.util.Objects;

/**
 * 推文领域命令的稳定回执。
 *
 * <p>{@code code} 是稳定错误码；成功为 {@link PostService#OK}。失败回执的
 * {@code postId} 可能为空，且不会携带正文。{@code revision} 对创建/状态变更是目标推文的
 * 新版本；对冲突失败是当前已知版本或 0。</p>
 */
public record PostCommandResult(
        boolean ok,
        String code,
        String message,
        String postId,
        long revision) {

    public PostCommandResult {
        code = Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
        if (revision < 0) {
            throw new IllegalArgumentException("revision 不能为负");
        }
    }

    public static PostCommandResult success(String postId, long revision) {
        return new PostCommandResult(true, PostService.OK, "操作成功", postId, revision);
    }

    public static PostCommandResult failure(String code, String message, String postId, long revision) {
        return new PostCommandResult(false, code, message, postId, revision);
    }
}
