package io.mosire.iibot.taskflow.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 宿主配置驱动的 QQ → 已认证领域身份解析器。 */
@FunctionalInterface
public interface IdentityResolver {

    Optional<MappedIdentity> resolve(String qqId);

    /**
     * 从不可变映射构造解析器；同一 playerId 被不同 QQ 映射时立即失败，避免“他人 QQ 能确认
     * 同一草稿”的配置漏洞。
     */
    static IdentityResolver fromMap(Map<String, MappedIdentity> byQq) {
        Objects.requireNonNull(byQq, "byQq");
        Map<String, MappedIdentity> copy = new LinkedHashMap<>();
        Map<String, String> qqByPlayer = new LinkedHashMap<>();
        for (Map.Entry<String, MappedIdentity> entry : byQq.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("QQ 映射不能含空键或空值");
            }
            if (!entry.getKey().equals(entry.getValue().qqId())) {
                throw new IllegalArgumentException("映射键与 MappedIdentity.qqId 不一致");
            }
            String previousQq = qqByPlayer.putIfAbsent(entry.getValue().profile().playerId(),
                    entry.getKey());
            if (previousQq != null && !previousQq.equals(entry.getKey())) {
                throw new IllegalArgumentException("同一 playerId 不能映射多个 QQ");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        Map<String, MappedIdentity> immutable = Map.copyOf(copy);
        return qqId -> qqId == null ? Optional.empty()
                : Optional.ofNullable(immutable.get(qqId));
    }
}
