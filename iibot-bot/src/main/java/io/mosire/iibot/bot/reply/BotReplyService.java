package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.store.MessageAppendResult;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.ops.OpsLog;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 按 thread 回复：宿主反解目标、写入 PENDING 出站消息、调用发送端口、回写三态。
 *
 * <p>同一 messageKey/幂等键已经存在时不再发送，直接返回既有状态。</p>
 */
public final class BotReplyService {

    public static final int MAX_TEXT_CHARS = 4000;

    private final MessageBoardStore board;
    private final BotReplyPort port;
    private final Clock clock;
    public BotReplyService(MessageBoardStore board, BotReplyPort port, Clock clock) {
        this.board = Objects.requireNonNull(board, "board");
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BotReplyResult reply(String threadId, String text, String replyToMessageId,
                                String idempotencyKey) {
        return reply(threadId, text, replyToMessageId, false, idempotencyKey);
    }

    public BotReplyResult reply(String threadId, String text, String replyToMessageId,
                                boolean mentionSender, String idempotencyKey) {
        ChatThread thread = board.thread(requireText(threadId, "threadId"))
                .orElseThrow(() -> new BotReplyException("THREAD_NOT_FOUND",
                        "thread 不存在"));
        String normalizedText = requireText(text, "text");
        if (normalizedText.length() > MAX_TEXT_CHARS) {
            throw new BotReplyException("TEXT_TOO_LONG",
                    "回复正文超过 " + MAX_TEXT_CHARS + " 字符");
        }
        String key = requireText(idempotencyKey, "idempotencyKey");
        String normalizedReplyTo = replyToMessageId == null ? "" : replyToMessageId.trim();
        String mentionQqId = "";
        if (mentionSender) {
            if (thread.source().chatType() != ChatType.GROUP) {
                throw new BotReplyException("MENTION_REQUIRES_GROUP", "@ 只适用于群聊");
            }
            if (normalizedReplyTo.isEmpty()) {
                throw new BotReplyException("MENTION_TARGET_REQUIRED",
                        "mentionSender 需要 replyToMessageId 定位发送者");
            }
            ChatMessage quoted = board.findByMessageId(thread.threadId(), normalizedReplyTo)
                    .orElseThrow(() -> new BotReplyException("MENTION_TARGET_NOT_FOUND",
                            "被回复消息不在该 thread"));
            if (quoted.direction() != ChatDirection.IN) {
                throw new BotReplyException("MENTION_TARGET_INVALID",
                        "只能 @ 入站消息的发送者");
            }
            mentionQqId = quoted.senderId();
        }
        String nonTextSummary = (normalizedReplyTo.isEmpty() ? "" : "[引用×1]")
                + (mentionQqId.isEmpty() ? "" : "[@×1]");
        ChatMessage candidate = ChatMessage.outbound(thread.source(), key, clock.instant(),
                normalizedText, nonTextSummary, normalizedReplyTo.isEmpty()
                        ? null : normalizedReplyTo);
        var existing = board.find(candidate.messageKey());
        if (existing.isPresent()) {
            ChatMessage message = existing.orElseThrow();
            OpsLog.info("event=reply_idempotent_hit threadId={} key={} state={}",
                    thread.source().threadId(), key, message.deliveryState());
            return new BotReplyResult(message, true, message.deliveryState(),
                    List.of(), "");
        }

        List<MessageSegment> segments = new ArrayList<>();
        int segmentIndex = 0;
        if (!normalizedReplyTo.isEmpty()) {
            segments.add(MessageSegment.reply(segmentIndex++, normalizedReplyTo));
        }
        if (!mentionQqId.isEmpty()) {
            segments.add(MessageSegment.mention(segmentIndex++, mentionQqId));
        }
        segments.add(MessageSegment.text(segmentIndex, normalizedText));
        MessageAppendResult appended = board.append(candidate, segments);
        if (!appended.created()) {
            ChatMessage message = appended.message();
            OpsLog.info("event=reply_idempotent_hit threadId={} key={} state={}",
                    thread.source().threadId(), key, message.deliveryState());
            return new BotReplyResult(message, true, message.deliveryState(),
                    List.of(), "");
        }

        BotReplySendResult sendResult;
        try {
            sendResult = port.sendWithMention(thread.source(), normalizedText,
                    normalizedReplyTo.isEmpty() ? null : normalizedReplyTo,
                    mentionQqId, key);
        } catch (RuntimeException failure) {
            board.updateDelivery(appended.message().messageKey(),
                    DeliveryState.UNKNOWN, null, null);
            OpsLog.error("event=reply_send threadId={} key={} state=UNKNOWN "
                            + "code=REPLY_SEND_UNKNOWN errorType={}",
                    thread.source().threadId(), key, failure.getClass().getSimpleName());
            throw new BotReplyException("REPLY_SEND_UNKNOWN",
                    "发送过程异常，已记录 UNKNOWN，禁止自动重发", failure);
        }

        ChatMessage updated = board.updateDelivery(
                appended.message().messageKey(),
                sendResult.state(),
                sendResult.messageIds().isEmpty() ? null : sendResult.messageIds().getFirst(),
                clock.instant());
        OpsLog.info("event=reply_send threadId={} key={} state={} code={} messageIds={}",
                thread.source().threadId(), key, sendResult.state(),
                sendResult.errorCode() == null ? "" : sendResult.errorCode(),
                sendResult.messageIds());
        return new BotReplyResult(updated, false, sendResult.state(),
                sendResult.messageIds(), sendResult.errorCode());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BotReplyException("INVALID_ARGUMENTS", name + " 不能为空");
        }
        return value.trim();
    }
}
