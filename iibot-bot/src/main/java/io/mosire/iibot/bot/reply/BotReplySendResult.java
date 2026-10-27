package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.DeliveryState;

import java.util.List;
import java.util.Objects;

/** 一次平台发送的最小三态结果。 */
public record BotReplySendResult(
        DeliveryState state,
        List<String> messageIds,
        String errorCode) {

    public BotReplySendResult {
        state = Objects.requireNonNull(state, "state");
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        errorCode = errorCode == null ? "" : errorCode.trim();
        if (state == DeliveryState.NONE || state == DeliveryState.PENDING) {
            throw new IllegalArgumentException("发送结果必须是 SENT/FAILED_BEFORE_SEND/UNKNOWN");
        }
        if (state == DeliveryState.SENT && messageIds.isEmpty()) {
            throw new IllegalArgumentException("SENT 必须包含 messageId");
        }
        if (state != DeliveryState.SENT && errorCode.isEmpty()) {
            throw new IllegalArgumentException("非 SENT 必须包含 errorCode");
        }
    }
}
