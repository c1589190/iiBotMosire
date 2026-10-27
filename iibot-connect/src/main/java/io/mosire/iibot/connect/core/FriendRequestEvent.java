package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;

/** OneBot friend-request 事件；flag 只允许本地持久化，不返回模型。 */
public record FriendRequestEvent(
        String flag,
        String userId,
        String nickname,
        String comment,
        Instant receivedAt) {

    public FriendRequestEvent {
        flag = requireText(flag, "flag");
        userId = requireText(userId, "userId");
        nickname = nickname == null ? "" : nickname.trim();
        comment = comment == null ? "" : comment.trim();
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value.trim();
    }
}
