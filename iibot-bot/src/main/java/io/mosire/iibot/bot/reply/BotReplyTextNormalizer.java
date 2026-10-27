package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatType;

/** 回复正文在落库和发送前的统一规范化端口。 */
@FunctionalInterface
public interface BotReplyTextNormalizer {

    /**
     * @return 实际允许落库/发送的正文；不得返回 null
     */
    String normalize(String text, ChatType chatType);

    static BotReplyTextNormalizer identity() {
        return (text, chatType) -> text;
    }
}
