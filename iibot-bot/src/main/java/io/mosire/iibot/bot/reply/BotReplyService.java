package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.store.MessageAppendResult;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(BotReplyService.class);

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

    /**
     * 主动向任意 QQ 发起私聊；不要求 thread 已存在，先创建/复用 private thread，
     * 再把 OUT 消息写入消息板并发送。
     */
    public BotReplyResult sendPrivate(String accountId, String targetQqId, String text,
                                      String idempotencyKey) {
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("private-send"))) {
            String normalizedAccount = requireText(accountId, "accountId");
            String target = requireText(targetQqId, "targetQqId");
            String normalizedText = requireText(text, "text");
            if (normalizedText.length() > MAX_TEXT_CHARS) {
                throw new BotReplyException("TEXT_TOO_LONG",
                        "私聊正文超过 " + MAX_TEXT_CHARS + " 字符");
            }
            String key = requireText(idempotencyKey, "idempotencyKey");
            ChatSourceKey source = ChatSourceKey.privateChat(normalizedAccount, target);
            ChatMessage candidate = ChatMessage.outbound(source, key, clock.instant(),
                    normalizedText, "", null);
            var existing = board.find(candidate.messageKey());
            if (existing.isPresent()) {
                ChatMessage message = existing.orElseThrow();
                return new BotReplyResult(message, true, message.deliveryState(),
                        List.of(), "");
            }
            MessageAppendResult appended = board.append(candidate,
                    List.of(MessageSegment.text(0, normalizedText)));
            if (!appended.created()) {
                ChatMessage message = appended.message();
                return new BotReplyResult(message, true, message.deliveryState(),
                        List.of(), "");
            }
            BotReplySendResult sendResult;
            try {
                sendResult = port.send(source, normalizedText, "", key);
            } catch (RuntimeException failure) {
                board.updateDelivery(appended.message().messageKey(),
                        DeliveryState.UNKNOWN, null, null);
                throw new BotReplyException("PRIVATE_SEND_UNKNOWN",
                        "主动私聊发送过程异常，已记录 UNKNOWN，禁止自动重发", failure);
            }
            ChatMessage updated = board.updateDelivery(
                    appended.message().messageKey(), sendResult.state(),
                    sendResult.messageIds().isEmpty() ? null
                            : sendResult.messageIds().getFirst(),
                    clock.instant());
            OpsLog.info("event=private_send accountId={} targetQqId={} key={} state={} "
                            + "code={}", normalizedAccount, target, key, sendResult.state(),
                    sendResult.errorCode() == null ? "" : sendResult.errorCode());
            return new BotReplyResult(updated, false, sendResult.state(),
                    sendResult.messageIds(), sendResult.errorCode());
        }
    }

    public BotReplyResult reply(String threadId, String text, String replyToMessageId,
                                boolean mentionSender, String idempotencyKey) {
        return reply(threadId, text, replyToMessageId, mentionSender, false,
                idempotencyKey);
    }

    public BotReplyResult reply(String threadId, String text, String replyToMessageId,
                                boolean mentionSender, boolean mentionAll,
                                String idempotencyKey) {
        return replyRich(threadId, text, replyToMessageId, mentionSender, mentionAll,
                List.of(), idempotencyKey);
    }

    /**
     * 回复一个已有 thread，可附带受控图片/表情段。文本与附件不能同时为空。
     */
    public BotReplyResult replyRich(String threadId, String text, String replyToMessageId,
                                    boolean mentionSender, boolean mentionAll,
                                    List<OutboundSegment> attachments,
                                    String idempotencyKey) {
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("reply"))) {
            return replyScoped(threadId, text, replyToMessageId, mentionSender,
                    mentionAll, attachments, idempotencyKey);
        }
    }

    private BotReplyResult replyScoped(String threadId, String text, String replyToMessageId,
                                       boolean mentionSender, boolean mentionAll,
                                       List<OutboundSegment> attachments,
                                       String idempotencyKey) {
        ChatThread thread = board.thread(requireText(threadId, "threadId"))
                .orElseThrow(() -> new BotReplyException("THREAD_NOT_FOUND",
                        "thread 不存在"));
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        boolean hasText = text != null && !text.isBlank();
        if (!hasText && attachments.isEmpty()) {
            throw new BotReplyException("INVALID_ARGUMENTS", "text 与附件不能同时为空");
        }
        for (OutboundSegment attachment : attachments) {
            if (!(attachment instanceof OutboundSegment.Image)
                    && !(attachment instanceof OutboundSegment.Sticker)) {
                throw new BotReplyException("INVALID_ARGUMENTS",
                        "qq_conversation_send 只接受 image/sticker 附件");
            }
        }
        String normalizedText = hasText ? text.trim() : "";
        if (normalizedText.length() > MAX_TEXT_CHARS) {
            throw new BotReplyException("TEXT_TOO_LONG",
                    "回复正文超过 " + MAX_TEXT_CHARS + " 字符");
        }
        String key = requireText(idempotencyKey, "idempotencyKey");
        String normalizedReplyTo = replyToMessageId == null ? "" : replyToMessageId.trim();
        if (mentionAll && mentionSender) {
            throw new BotReplyException("INVALID_ARGUMENTS",
                    "mentionAll 与 mentionSender 不能同时为 true");
        }
        if (mentionAll && thread.source().chatType() != ChatType.GROUP) {
            throw new BotReplyException("MENTION_ALL_REQUIRES_GROUP",
                    "@全体只适用于群聊");
        }
        LOG.debug("event=reply_routing threadId={} key={} mention={} mentionAll={} "
                        + "hasReplyTo={}", thread.source().threadId(), key, mentionSender,
                mentionAll, !normalizedReplyTo.isEmpty());
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
        long imageCount = attachments.stream()
                .filter(OutboundSegment.Image.class::isInstance).count();
        long stickerCount = attachments.stream()
                .filter(OutboundSegment.Sticker.class::isInstance).count();
        String nonTextSummary = (normalizedReplyTo.isEmpty() ? "" : "[引用×1]")
                + (mentionQqId.isEmpty() ? "" : "[@×1]")
                + (mentionAll ? "[@全体×1]" : "")
                + (imageCount == 0L ? "" : "[图片×" + imageCount + "]")
                + (stickerCount == 0L ? "" : "[表情×" + stickerCount + "]");
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
        if (mentionAll) {
            segments.add(MessageSegment.mention(segmentIndex++, "all"));
        } else if (!mentionQqId.isEmpty()) {
            segments.add(MessageSegment.mention(segmentIndex++, mentionQqId));
        }
        if (!normalizedText.isEmpty()) {
            segments.add(MessageSegment.text(segmentIndex++, normalizedText));
        }
        for (OutboundSegment attachment : attachments) {
            if (attachment instanceof OutboundSegment.Image image) {
                segments.add(new MessageSegment(segmentIndex++,
                        MessageSegment.Type.IMAGE, image.fileName(), "", "",
                        image.assetRef(), "", image.mediaType(), image.bytes().length,
                        "", "image"));
            } else if (attachment instanceof OutboundSegment.Sticker sticker) {
                segments.add(new MessageSegment(segmentIndex++,
                        MessageSegment.Type.FACE, "", sticker.faceId(), "", "", "", "",
                        0L, "", "face"));
            }
        }
        MessageAppendResult appended = board.append(candidate, segments);
        if (appended.created()) {
            LOG.debug("event=reply_state threadId={} key={} state=PENDING localSeq={} "
                            + "segments={}", thread.source().threadId(), key,
                    appended.message().localSeq(), segments.size());
        }
        if (!appended.created()) {
            ChatMessage message = appended.message();
            OpsLog.info("event=reply_idempotent_hit threadId={} key={} state={}",
                    thread.source().threadId(), key, message.deliveryState());
            return new BotReplyResult(message, true, message.deliveryState(),
                    List.of(), "");
        }

        BotReplySendResult sendResult;
        try {
            sendResult = port.sendRich(thread.source(), normalizedText,
                    normalizedReplyTo.isEmpty() ? null : normalizedReplyTo,
                    mentionQqId, mentionAll, attachments, key);
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
        LOG.debug("event=reply_state threadId={} key={} state={} messageIds={}",
                thread.source().threadId(), key, sendResult.state(),
                sendResult.messageIds());
        String resultCode = sendResult.errorCode() == null ? "" : sendResult.errorCode();
        switch (sendResult.state()) {
            case SENT -> OpsLog.info("event=reply_send threadId={} key={} state={} "
                            + "code={} messageIds={}", thread.source().threadId(), key,
                    sendResult.state(), resultCode, sendResult.messageIds());
            case FAILED_BEFORE_SEND -> OpsLog.warn("event=reply_send threadId={} key={} "
                            + "state={} code={} messageIds={}", thread.source().threadId(),
                    key, sendResult.state(), resultCode, sendResult.messageIds());
            case UNKNOWN -> OpsLog.error("event=reply_send threadId={} key={} state={} "
                            + "code={} messageIds={} retry=forbidden",
                    thread.source().threadId(), key, sendResult.state(), resultCode,
                    sendResult.messageIds());
        }
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
