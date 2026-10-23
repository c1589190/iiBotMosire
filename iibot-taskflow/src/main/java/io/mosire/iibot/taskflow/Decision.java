package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家在同一私聊中通过硬命令确认后的有效决策。
 *
 * <p>contentHash 与草稿预览绑定；confirmedByPlayerId 独立保存，便于审计“是否由
 * 本人确认”。Decision 一旦生成便不可变，并随回合冻结进入 TurnSnapshot。</p>
 */
public record Decision(
        String decisionId,
        String draftId,
        String turnId,
        String playerId,
        String contentHash,
        String confirmedByPlayerId,
        Instant confirmedAt,
        Map<String, Object> fields) {

    public Decision {
        if (decisionId == null || decisionId.isBlank() || draftId == null || draftId.isBlank()
                || turnId == null || turnId.isBlank() || playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("decisionId/draftId/turnId/playerId 不能为空");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash 不能为空");
        }
        if (!playerId.equals(confirmedByPlayerId)) {
            throw new IllegalArgumentException("确认者必须等于决策玩家");
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        fields = fields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fields));
    }
}
