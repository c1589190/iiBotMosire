package io.mosire.iibot.taskflow.complaint;

import java.time.Instant;
import java.util.Objects;

/**
 * 投诉状态变更的审计摘要。
 *
 * <p>本记录只保存动作、相关 ID、版本、内容摘要哈希与时间；不保存投诉标题、正文或 GM
 * 回应正文。{@code contentHash} 对提交是正文 SHA-256，对回应是回应正文 SHA-256，对投递
 * 是投递状态标记的 SHA-256。</p>
 */
public record ComplaintAuditEntry(
        ComplaintAuditAction action,
        String complaintId,
        String playerId,
        String actorId,
        long revision,
        String contentHash,
        Instant at) {

    public ComplaintAuditEntry {
        Objects.requireNonNull(action, "action");
        if (complaintId == null || complaintId.isBlank()) {
            throw new IllegalArgumentException("complaintId 不能为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须为正");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash 不能为空");
        }
        Objects.requireNonNull(at, "at");
    }
}
