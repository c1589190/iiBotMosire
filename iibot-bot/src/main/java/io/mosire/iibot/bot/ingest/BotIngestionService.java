package io.mosire.iibot.bot.ingest;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.store.MessageAppendResult;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.bot.weight.MessageWeightDecision;
import io.mosire.iibot.bot.weight.SourceWeightPolicy;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 把协议中立的 InboundMessage 写入 bot 消息板，并触发 thread 的防抖摘要 flush。
 *
 * <p>身份只来自 adapter 已解析的 selfId/userId/groupId；聊天文本不参与身份判断。</p>
 */
public final class BotIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(BotIngestionService.class);

    private final MessageBoardStore board;
    private final DigestTrigger digestTrigger;
    private final SourceWeightService weights;

    public BotIngestionService(MessageBoardStore board, Consumer<String> digestTrigger) {
        this(board, (threadId, delayMillis) -> digestTrigger.accept(threadId), null);
    }

    public BotIngestionService(MessageBoardStore board, DigestTrigger digestTrigger,
                               SourceWeightService weights) {
        this.board = Objects.requireNonNull(board, "board");
        this.digestTrigger = Objects.requireNonNull(digestTrigger, "digestTrigger");
        this.weights = weights;
    }

    /** 记录一条入站消息；重复 messageKey 不会再次触发 digest。 */
    public IngestResult ingest(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("inbound"))) {
            return ingestScoped(message);
        }
    }

    private IngestResult ingestScoped(InboundMessage message) {
        ChatSourceKey source = sourceOf(message);
        LOG.debug("event=inbound_received threadId={} messageKey={} chatType={} "
                        + "segments={} hasReply={}",
                source.threadId(), message.messageId(), source.chatType(),
                message.segments().size(), !message.replyToMessageId().isEmpty());
        ChatMessage candidate = ChatMessage.inbound(
                source,
                message.messageId(),
                message.userId(),
                message.senderName(),
                message.receivedAt(),
                message.receivedAt(),
                message.text(),
                message.nonTextSummary(),
                message.replyToMessageId());
        MessageAppendResult result;
        try {
            result = board.append(candidate, message.segments());
            LOG.debug("event=inbound_persist threadId={} messageKey={} localSeq={} "
                            + "created={}", source.threadId(), result.message().messageKey(),
                    result.message().localSeq(), result.created());
        } catch (RuntimeException failure) {
            OpsLog.error("event=inbound_ingest threadId={} messageKey={} outcome=error "
                            + "errorType={}", source.threadId(), candidate.messageKey(),
                    failure.getClass().getSimpleName());
            throw failure;
        }
        if (result.created()) {
            long delayMillis = 0L;
            if (weights != null) {
                ChatThread thread = board.thread(source.threadId()).orElseThrow();
                SourceWeightPolicy policy = weights.ensurePolicy(
                        source, thread.messageCount() > 1L);
                MessageWeightDecision decision = weights.evaluate(
                        message, result.message(), policy);
                weights.saveDecision(decision);
                delayMillis = weights.enqueueDelayMillis(policy, decision);
            }
            OpsLog.info("event=inbound_ingest threadId={} messageKey={} localSeq={} "
                            + "chatType={} outcome=accepted delayMs={}",
                    source.threadId(), result.message().messageKey(),
                    result.message().localSeq(), source.chatType(), delayMillis);
            LOG.debug("event=inbound_digest_scheduled threadId={} delayMs={}",
                    source.threadId(), delayMillis);
            try {
                digestTrigger.enqueue(result.message().source().threadId(), delayMillis);
            } catch (RuntimeException failure) {
                OpsLog.error("event=inbound_digest_enqueue_failed threadId={} "
                                + "messageKey={} errorType={}", source.threadId(),
                        result.message().messageKey(), failure.getClass().getSimpleName());
                throw failure;
            }
        } else {
            OpsLog.info("event=inbound_ingest threadId={} messageKey={} localSeq={} "
                            + "chatType={} outcome=duplicate",
                    source.threadId(), result.message().messageKey(),
                    result.message().localSeq(), source.chatType());
            LOG.debug("event=inbound_dedupe_hit threadId={} messageKey={}",
                    source.threadId(), result.message().messageKey());
        }
        return new IngestResult(result.message(), result.created());
    }

    private static ChatSourceKey sourceOf(InboundMessage message) {
        return switch (message.sessionType()) {
            case PRIVATE -> ChatSourceKey.privateChat(message.selfId(), message.userId());
            case GROUP -> ChatSourceKey.groupChat(message.selfId(), message.groupId());
        };
    }

    /** 调度器入口；delay=0 表示使用默认防抖窗口。 */
    @FunctionalInterface
    public interface DigestTrigger {
        void enqueue(String threadId, long delayMillis);
    }

    /** 入库结果。 */
    public record IngestResult(ChatMessage message, boolean created) {
        public IngestResult {
            if (message == null) {
                throw new IllegalArgumentException("message 不能为空");
            }
        }
    }
}
