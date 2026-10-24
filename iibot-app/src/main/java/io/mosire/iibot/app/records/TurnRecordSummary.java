package io.mosire.iibot.app.records;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 回合记录列表摘要；不包含任何正文或物理路径。 */
public record TurnRecordSummary(
        int turnNo,
        String turnId,
        String status,
        long worldVersion,
        Instant openedAt,
        Instant closedAt,
        List<String> batchIds,
        List<String> packageIds,
        List<String> postIds,
        List<String> publicationIds) {

    public TurnRecordSummary {
        if (turnNo <= 0) {
            throw new IllegalArgumentException("turnNo 必须为正");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (worldVersion < 0) {
            throw new IllegalArgumentException("worldVersion 不能为负");
        }
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(closedAt, "closedAt");
        batchIds = immutable(batchIds);
        packageIds = immutable(packageIds);
        postIds = immutable(postIds);
        publicationIds = immutable(publicationIds);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
