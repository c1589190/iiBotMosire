package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.Objects;

/** 玩家向秘书发起、等待 GM 过滤后答复的信息请求。 */
public record InfoRequest(
        String requestId,
        String playerId,
        String turnId,
        String question,
        Instant createdAt,
        InfoRequestStatus status) {

    public InfoRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
    }
}
