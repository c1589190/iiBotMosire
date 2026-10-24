package io.mosire.iibot.taskflow.decision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一个裁定包被批准后形成的行动批次。当前只作为提交结果记录，不触发 LLM 推演。
 *
 * <p>{@code actionIds/texts/playerIds} 一一对应、同序；{@code turnId} 可为 null。正文只
 * 供宿主执行器使用，{@link #toString()} 不包含正文。</p>
 */
public record ActionBatch(
        String batchId,
        String packageId,
        String turnId,
        List<String> actionIds,
        List<String> texts,
        List<String> playerIds,
        Instant createdAt,
        ActionBatchStatus status,
        String result) {

    public ActionBatch {
        requireText(batchId, "batchId");
        requireText(packageId, "packageId");
        Objects.requireNonNull(actionIds, "actionIds");
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(playerIds, "playerIds");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
        for (String actionId : actionIds) {
            requireText(actionId, "actionIds 含空值");
        }
        for (String text : texts) {
            Objects.requireNonNull(text, "texts 含 null");
        }
        for (String playerId : playerIds) {
            requireText(playerId, "playerIds 含空值");
        }
        actionIds = List.copyOf(actionIds);
        texts = List.copyOf(texts);
        playerIds = List.copyOf(playerIds);
        if (actionIds.size() != texts.size() || actionIds.size() != playerIds.size()) {
            throw new IllegalArgumentException("actionIds/texts/playerIds 必须一一对应且等长");
        }
    }

    public ActionBatch withStatus(ActionBatchStatus nextStatus, String nextResult) {
        return new ActionBatch(batchId, packageId, turnId, actionIds, texts, playerIds,
                createdAt, nextStatus, nextResult);
    }

    /** 绑定执行该批次的 engine turnId；null 表示执行时没有当前回合。 */
    public ActionBatch withTurnId(String nextTurnId) {
        return new ActionBatch(batchId, packageId, nextTurnId, actionIds, texts, playerIds,
                createdAt, status, result);
    }

    public ActionBatch committed(String summary) {
        return withStatus(ActionBatchStatus.COMMITTED, summary);
    }

    @Override
    public String toString() {
        return "ActionBatch[batchId=" + batchId
                + ", packageId=" + packageId
                + ", turnId=" + turnId
                + ", actionCount=" + actionIds.size()
                + ", createdAt=" + createdAt
                + ", status=" + status
                + ", result=" + result
                + ']';
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
