package io.mosire.iibot.taskflow.complaint;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条玩家投诉的不可变快照。
 *
 * <p>{@code playerId} 只能由 {@link ComplaintService} 从宿主已认证的 PLAYER actor 写入。
 * {@code body} 与 {@code responseBody} 属于私密正文，不进入事件 payload、审计摘要或默认
 * 日志；{@link #toString()} 刻意省略二者。</p>
 *
 * @param deliveryState 回应写入后由 outbox 记录的投递状态；未回应时为 {@code null}
 * @param revision      单条投诉的聚合版本，从 1 开始，状态变更时递增
 */
public record Complaint(
        String complaintId,
        String playerId,
        String title,
        String body,
        ComplaintStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        String respondedBy,
        String responseBody,
        Instant respondedAt,
        ComplaintDeliveryState deliveryState) {

    public Complaint {
        if (complaintId == null || complaintId.isBlank()) {
            throw new IllegalArgumentException("complaintId 不能为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
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
        if (status == ComplaintStatus.SUBMITTED) {
            if (respondedBy != null || responseBody != null || respondedAt != null
                    || deliveryState != null) {
                throw new IllegalArgumentException("未回应投诉不能带回应或投递字段");
            }
        } else {
            if (respondedBy == null || respondedBy.isBlank()) {
                throw new IllegalArgumentException("已回应投诉必须有 respondedBy");
            }
            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalArgumentException("已回应投诉必须有 responseBody");
            }
            Objects.requireNonNull(respondedAt, "respondedAt");
            Objects.requireNonNull(deliveryState, "deliveryState");
        }
    }

    /** 正文与回应正文不进入默认日志/异常字符串。 */
    @Override
    public String toString() {
        return "Complaint[complaintId=" + complaintId
                + ", playerId=" + playerId
                + ", status=" + status
                + ", revision=" + revision
                + ", respondedBy=" + respondedBy
                + ", deliveryState=" + deliveryState + "]";
    }
}
