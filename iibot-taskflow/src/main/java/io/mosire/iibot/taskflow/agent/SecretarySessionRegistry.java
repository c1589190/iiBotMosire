package io.mosire.iibot.taskflow.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 每玩家秘书会话注册表；相同 playerId 不能注册两个会话。 */
public final class SecretarySessionRegistry {

    private final ConcurrentMap<String, SecretarySession> sessions = new ConcurrentHashMap<>();

    public SecretarySessionRegistry register(SecretarySession session) {
        Objects.requireNonNull(session, "session");
        SecretarySession previous = sessions.putIfAbsent(session.playerId(), session);
        if (previous != null) {
            throw new IllegalArgumentException("playerId 已注册秘书会话: " + session.playerId());
        }
        return this;
    }

    public Optional<SecretarySession> find(String playerId) {
        return playerId == null ? Optional.empty()
                : Optional.ofNullable(sessions.get(playerId));
    }

    public SecretarySession require(String playerId) {
        return find(playerId).orElseThrow(
                () -> new IllegalStateException("玩家没有秘书会话: " + playerId));
    }

    /**
     * 已注册会话的不可变快照，按 playerId 稳定排序。
     */
    public List<SecretarySession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(SecretarySession::playerId))
                .toList();
    }

    /**
     * 已注册玩家 ID 的不可变快照，按 playerId 稳定排序。
     */
    public List<String> playerIds() {
        return all().stream().map(SecretarySession::playerId).toList();
    }

    public int size() {
        return sessions.size();
    }
}
