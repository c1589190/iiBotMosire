package io.mosire.iibot.taskflow.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.iibot.taskflow.ActorRole;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 裁定包命令日志中的一条可重放命令。
 *
 * <p>content 会完整落 events.db；{@link #toString()} 只输出索引字段，不输出行动正文。</p>
 */
public record PackageCommand(
        CommandType commandType,
        String packageId,
        String actorId,
        ActorRole actorRole,
        String title,
        String proposerKey,
        List<PackageItem> items,
        Integer index,
        Set<Integer> approvedIndexes,
        Boolean approve,
        String note,
        long deadlineEpochMs,
        String idempotencyKey,
        CommitReceipt receipt,
        ActionBatch batch) {

    public enum CommandType {
        CREATE,
        APPEND,
        REPLACE_ITEM,
        REMOVE_ITEM,
        DECIDE,
        DECIDE_ITEM,
        COMMIT
    }

    public PackageCommand {
        Objects.requireNonNull(commandType, "commandType");
        if (packageId == null || packageId.isBlank()) {
            throw new IllegalArgumentException("packageId 不能为空");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        Objects.requireNonNull(actorRole, "actorRole");
        title = title == null ? "" : title;
        proposerKey = proposerKey == null ? "" : proposerKey;
        items = items == null ? List.of() : List.copyOf(items);
        approvedIndexes = approvedIndexes == null ? Set.of() : Set.copyOf(approvedIndexes);
        note = note == null ? "" : note;
        if (deadlineEpochMs < 0) {
            throw new IllegalArgumentException("deadlineEpochMs 不能为负");
        }
    }

    /** COMMIT APPLIED 时补上 receipt/batch；其余字段保持不变。 */
    public PackageCommand withCommitOutcome(CommitReceipt receipt, ActionBatch batch) {
        return new PackageCommand(commandType, packageId, actorId, actorRole, title,
                proposerKey, items, index, approvedIndexes, approve, note,
                deadlineEpochMs, idempotencyKey, receipt, batch);
    }

    @Override
    public String toString() {
        return "PackageCommand{commandType=" + commandType
                + ", packageId=" + packageId
                + ", actorId=" + actorId
                + ", actorRole=" + actorRole
                + ", itemCount=" + items.size()
                + ", index=" + index
                + ", approvedCount=" + approvedIndexes.size()
                + ", deadlineEpochMs=" + deadlineEpochMs
                + ", batchId=" + (batch == null ? null : batch.batchId())
                + ", receiptStatus=" + (receipt == null ? null : receipt.status())
                + '}';
    }
}
