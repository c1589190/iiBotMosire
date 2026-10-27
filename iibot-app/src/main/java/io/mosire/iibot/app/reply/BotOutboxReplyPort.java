package io.mosire.iibot.app.reply;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.SendOutcome;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplyPort;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.connect.core.OutboundSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把 bot core 的 thread reply 适配到现有 OutboxSender。
 *
 * <p>目标由 {@link ChatSourceKey} 决定，回复正文不包含任意目标参数。平台发送仍沿用
 * 既有正文守卫、全局节奏、三态回执和 UNKNOWN 不自动重发。</p>
 */
public final class BotOutboxReplyPort implements BotReplyPort {

    private final OutboxSender outbox;

    public BotOutboxReplyPort(OutboxSender outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public BotReplySendResult send(ChatSourceKey source, String text, String replyToMessageId,
                                   String idempotencyKey) {
        return sendWithMention(source, text, replyToMessageId, "", idempotencyKey);
    }

    @Override
    public BotReplySendResult sendWithMention(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, String idempotencyKey) {
        Objects.requireNonNull(source, "source");
        String correlationKey = "bot-reply:" + requireText(idempotencyKey, "idempotencyKey");
        boolean hasReply = replyToMessageId != null && !replyToMessageId.isBlank();
        boolean hasMention = mentionQqId != null && !mentionQqId.isBlank();
        if (!hasReply && !hasMention) {
            return map(source.chatType() == ChatType.PRIVATE
                    ? outbox.sendPrivateToUser(source.targetId(), List.of(text), correlationKey)
                    : outbox.broadcastToPublicGroup(
                            source.targetId(), List.of(text), correlationKey));
        }

        List<OutboundSegment> segments = new ArrayList<>();
        if (hasReply) {
            segments.add(new OutboundSegment.Reply(replyToMessageId.trim()));
        }
        if (hasMention) {
            segments.add(new OutboundSegment.Mention(mentionQqId.trim()));
        }
        segments.add(new OutboundSegment.Text(requireText(text, "text")));
        return map(source.chatType() == ChatType.PRIVATE
                ? outbox.sendRichPrivateToUser(source.targetId(), segments, correlationKey)
                : outbox.sendRichGroup(source.targetId(), segments, correlationKey));
    }

    private static BotReplySendResult map(List<SendOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return new BotReplySendResult(DeliveryState.UNKNOWN, List.of(),
                    "OUTBOX_EMPTY_RESULT");
        }
        SendOutcome outcome = outcomes.getFirst();
        DeliveryState state = switch (outcome.state()) {
            case SENT -> DeliveryState.SENT;
            case FAILED_BEFORE_SEND -> DeliveryState.FAILED_BEFORE_SEND;
            case UNKNOWN -> DeliveryState.UNKNOWN;
        };
        return new BotReplySendResult(state, outcome.adapterMessageIds(),
                outcome.errorCode() == null ? "" : outcome.errorCode());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
