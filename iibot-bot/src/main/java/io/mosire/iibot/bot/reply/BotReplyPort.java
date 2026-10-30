package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.connect.core.OutboundSegment;

import java.util.List;

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

    /**
     * 带 mentionSender 或 mentionAll 的回复。旧实现未覆盖时，mentionAll 明确失败而不是
     * 静默退化成不带 @全体的消息。
     */
    default BotReplySendResult sendWithMentions(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, boolean mentionAll, String idempotencyKey) {
        if (mentionAll) {
            return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND,
                    List.of(), "MENTION_ALL_UNSUPPORTED");
        }
        return sendWithMention(source, text, replyToMessageId, mentionQqId, idempotencyKey);
    }

    /**
     * 带图片/表情附件与 mention 的富消息。旧实现没有媒体解析能力时明确失败，
     * 不允许静默丢掉附件。
     */
    default BotReplySendResult sendRich(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, boolean mentionAll, List<OutboundSegment> attachments,
            String idempotencyKey) {
        if (attachments != null && !attachments.isEmpty()) {
            return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND,
                    List.of(), "RICH_SEND_UNSUPPORTED");
        }
        return sendWithMentions(source, text, replyToMessageId, mentionQqId,
                mentionAll, idempotencyKey);
    }
}
