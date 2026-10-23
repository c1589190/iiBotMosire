package io.mosire.iibot.taskflow.complaint;

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
 * 投诉领域的完整不可变持久化快照。
 *
 * <p>投诉与回应正文可以保存在 {@link Complaint} 中供版本化存储保存，但审计条目只含动作、
 * ID、版本、哈希与时间。幂等回执随快照保存，从而支持 store 重建后继续使用同一幂等键。</p>
 */
public record ComplaintSnapshot(
        List<Complaint> complaints,
        Map<String, ComplaintCommandResult> idempotentResults,
        List<ComplaintAuditEntry> auditLog) {

    public ComplaintSnapshot {
        complaints = complaints == null ? List.of() : List.copyOf(complaints);
        idempotentResults = immutableLinkedMap(idempotentResults);
        auditLog = auditLog == null ? List.of() : List.copyOf(auditLog);
        Set<String> ids = new LinkedHashSet<>();
        for (Complaint complaint : complaints) {
            Objects.requireNonNull(complaint, "complaint");
            if (!ids.add(complaint.complaintId())) {
                throw new IllegalArgumentException("重复的 complaintId: " + complaint.complaintId());
            }
        }
        for (Map.Entry<String, ComplaintCommandResult> entry : idempotentResults.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("非法 idempotentResults 条目");
            }
        }
    }

    public static ComplaintSnapshot empty() {
        return new ComplaintSnapshot(List.of(), Map.of(), List.of());
    }

    public Optional<Complaint> findComplaint(String complaintId) {
        if (complaintId == null || complaintId.isBlank()) {
            return Optional.empty();
        }
        return complaints.stream()
                .filter(complaint -> complaint.complaintId().equals(complaintId))
                .findFirst();
    }

    public int size() {
        return complaints.size();
    }

    /** 创建新快照并替换/追加一条投诉；不修改当前快照。 */
    public ComplaintSnapshot withComplaint(Complaint complaint) {
        Objects.requireNonNull(complaint, "complaint");
        List<Complaint> next = new ArrayList<>(complaints.size() + 1);
        boolean replaced = false;
        for (Complaint existing : complaints) {
            if (existing.complaintId().equals(complaint.complaintId())) {
                next.add(complaint);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(complaint);
        }
        return new ComplaintSnapshot(next, idempotentResults, auditLog);
    }

    public ComplaintSnapshot withIdempotentResult(String idempotencyKey,
                                                   ComplaintCommandResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        Objects.requireNonNull(result, "result");
        Map<String, ComplaintCommandResult> next = new LinkedHashMap<>(idempotentResults);
        next.put(idempotencyKey, result);
        return new ComplaintSnapshot(complaints, next, auditLog);
    }

    public ComplaintSnapshot withAuditEntry(ComplaintAuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        List<ComplaintAuditEntry> next = new ArrayList<>(auditLog.size() + 1);
        next.addAll(auditLog);
        next.add(entry);
        return new ComplaintSnapshot(complaints, idempotentResults, next);
    }

    private static <V> Map<String, V> immutableLinkedMap(Map<String, V> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
