package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatSourceKey;

/** 外部 QQ 发送端口；实现负责真实 adapter/outbox，core 只保存结果。 */
@FunctionalInterface
public interface BotReplyPort {

    BotReplySendResult send(ChatSourceKey source, String text, String replyToMessageId,
                            String idempotencyKey);

    /**
     * 带宿主解析出的 mention 目标的回复；默认忽略 mention，兼容旧 fake。
     */
    default BotReplySendResult sendWithMention(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, String idempotencyKey) {
        return send(source, text, replyToMessageId, idempotencyKey);
    }
}
