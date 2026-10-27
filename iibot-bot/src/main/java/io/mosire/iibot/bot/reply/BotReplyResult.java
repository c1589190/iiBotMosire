package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.DeliveryState;

import java.util.List;

/** 一次 bot_reply 的可审计结果。 */
public record BotReplyResult(
        ChatMessage message,
        boolean alreadyAttempted,
        DeliveryState deliveryState,
        List<String> messageIds,
        String errorCode) {

    public BotReplyResult {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (deliveryState == null) {
            throw new IllegalArgumentException("deliveryState 不能为空");
        }
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        errorCode = errorCode == null ? "" : errorCode.trim();
    }
}
