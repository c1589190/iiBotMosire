package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 由秘书生成、等待玩家硬确认的结构化决策草稿。
 *
 * <p>previewHash 必须由玩家确认；这样即使草稿在聊天中被重新渲染或篡改，也不能
 * 把玩家没看过的内容提交为有效 Decision。</p>
 */
public record DecisionDraft(
        String draftId,
        String turnId,
        String playerId,
        String intent,
        List<String> actions,
        Map<String, String> resources,
        String target,
        String contingency,
        String previewHash,
        DraftStatus status,
        long revision,
        Instant expiresAt) {

    public DecisionDraft {
        if (draftId == null || draftId.isBlank() || turnId == null || turnId.isBlank()
                || playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("draftId/turnId/playerId 不能为空");
        }
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("intent 不能为空");
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
        resources = resources == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resources));
        target = target == null ? "" : target;
        contingency = contingency == null ? "" : contingency;
        if (previewHash == null || previewHash.isBlank()) {
            throw new IllegalArgumentException("previewHash 不能为空");
        }
        Objects.requireNonNull(status, "status");
        if (revision < 0) {
            throw new IllegalArgumentException("revision 不能为负");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public DecisionDraftInput input() {
        return new DecisionDraftInput(intent, actions, resources, target, contingency, expiresAt);
    }
}
