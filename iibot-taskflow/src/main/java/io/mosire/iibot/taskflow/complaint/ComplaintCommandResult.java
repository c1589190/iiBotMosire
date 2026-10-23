package io.mosire.iibot.taskflow.complaint;

import java.util.Objects;

/**
 * 投诉领域命令的稳定回执。
 *
 * <p>{@code code} 是稳定错误码；成功为 {@link ComplaintService#OK}。失败回执不携带投诉
 * 正文或 GM 回应正文。</p>
 */
public record ComplaintCommandResult(
        boolean ok,
        String code,
        String message,
        String complaintId,
        long revision) {

    public ComplaintCommandResult {
        code = Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
        if (revision < 0) {
            throw new IllegalArgumentException("revision 不能为负");
        }
    }

    public static ComplaintCommandResult success(String complaintId, long revision) {
        return new ComplaintCommandResult(true, ComplaintService.OK, "操作成功",
                complaintId, revision);
    }

    public static ComplaintCommandResult failure(String code, String message,
                                                  String complaintId, long revision) {
        return new ComplaintCommandResult(false, code, message, complaintId,
                Math.max(0L, revision));
    }
}
