package io.mosire.iibot.taskflow.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 行动池的不可变持久化快照。
 *
 * <p>{@code idempotentResults} 按玩家分桶，再以提交时提供的 idempotencyKey 映射到首次生成的
 * actionId。这样同一 key 不会跨玩家命中，也便于 app 后续直接序列化为 JSON。快照本身包含行动
 * 正文（持久化必需）；展示层只读取摘要字段，不得把 {@link PlayerAction#text()} 写入日志。</p>
 */
public record ActionPoolSnapshot(
        List<PlayerAction> actions,
        Map<String, Map<String, String>> idempotentResults) {

    public ActionPoolSnapshot {
        Objects.requireNonNull(actions, "actions");
        for (PlayerAction action : actions) {
            Objects.requireNonNull(action, "actions 含 null");
        }
        actions = List.copyOf(actions);
        Objects.requireNonNull(idempotentResults, "idempotentResults");
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : idempotentResults.entrySet()) {
            String playerId = Objects.requireNonNull(entry.getKey(), "idempotentResults 含 null 玩家");
            Map<String, String> inner = Objects.requireNonNull(entry.getValue(),
                    "idempotentResults[" + playerId + "]");
            Map<String, String> innerCopy = new LinkedHashMap<>();
            for (Map.Entry<String, String> result : inner.entrySet()) {
                innerCopy.put(Objects.requireNonNull(result.getKey(), "idempotencyKey"),
                        Objects.requireNonNull(result.getValue(), "actionId"));
            }
            copy.put(playerId, Map.copyOf(innerCopy));
        }
        idempotentResults = Map.copyOf(copy);
    }

    public static ActionPoolSnapshot empty() {
        return new ActionPoolSnapshot(List.of(), Map.of());
    }

    public Optional<PlayerAction> action(String actionId) {
        return actions.stream().filter(action -> action.actionId().equals(actionId)).findFirst();
    }

    public Optional<String> idempotentActionId(String playerId, String idempotencyKey) {
        Map<String, String> playerResults = idempotentResults.get(playerId);
        if (playerResults == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(playerResults.get(idempotencyKey));
    }
}
