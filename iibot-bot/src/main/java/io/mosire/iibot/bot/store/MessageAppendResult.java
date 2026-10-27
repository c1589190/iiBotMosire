package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.chat.ChatMessage;

/** 消息板 append 的结果；重复消息返回已有记录且 {@code created=false}。 */
public record MessageAppendResult(ChatMessage message, boolean created) {

    public MessageAppendResult {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}
