package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结构化决策草稿输入；领域层不解析自然语言。
 *
 * <p>actions 与 resources 会做防御性复制，确保确认后的 Decision 与快照不会因为
 * 调用方继续修改集合而漂移。</p>
 */
public record DecisionDraftInput(
        String intent,
        List<String> actions,
        Map<String, String> resources,
        String target,
        String contingency,
        Instant expiresAt) {

    public DecisionDraftInput {
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("intent 不能为空");
        }
        Objects.requireNonNull(actions, "actions");
        if (actions.stream().anyMatch(action -> action == null || action.isBlank())) {
            throw new IllegalArgumentException("actions 不能含空值");
        }
        actions = List.copyOf(actions);
        Objects.requireNonNull(resources, "resources");
        if (resources.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey().isBlank()
                || e.getValue() == null || e.getValue().isBlank())) {
            throw new IllegalArgumentException("resources 不能含空键或空值");
        }
        resources = Map.copyOf(new LinkedHashMap<>(resources));
        target = target == null ? "" : target;
        contingency = contingency == null ? "" : contingency;
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** 用于 previewHash 的稳定文本表示。 */
    public String canonicalText() {
        return "intent=" + intent
                + "|actions=" + String.join("\u001f", actions)
                + "|resources=" + resources.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + "\u001e" + b).orElse("")
                + "|target=" + target
                + "|contingency=" + contingency
                + "|expiresAt=" + expiresAt;
    }
}
