package io.mosire.iibot.bot.friend;

import java.time.Instant;
import java.util.Objects;

/** 可返回给模型的脱敏好友申请记录，不包含 raw OneBot flag。 */
public record FriendRequestRecord(
        String requestId,
        String userId,
        String displayName,
        String comment,
        Instant receivedAt,
        String status,
        String remark,
        Instant decidedAt) {

    public FriendRequestRecord {
        requestId = requireText(requestId, "requestId");
        userId = requireText(userId, "userId");
        displayName = displayName == null ? "" : displayName;
        comment = comment == null ? "" : comment;
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        status = requireText(status, "status");
        remark = remark == null ? "" : remark;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value.trim();
    }
}
