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

    /**
     * 记录一条入站消息；重复 messageKey 不会再次触发 digest。
     *
     * <p>兼容测试/单账号调用：期望账号由消息自身的 selfId 派生。生产 adapter handler
     * 必须使用 {@link #ingest(String, InboundMessage)} 显式绑定账号。</p>
     */
    public IngestResult ingest(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        return ingest(message.selfId(), message);
    }

    /**
     * 按 adapter 绑定的预期账号记录一条入站消息。
     *
     * <p>消息 selfId 与 expectedAccountId 不一致时 fail-closed：不写消息板、不触发 digest、
     * 不向 adapter 线程抛异常，只记录稳定的 {@code inbound_account_mismatch} 运维事件。</p>
     *
     * @param expectedAccountId adapter 注册时绑定的稳定 accountId
     * @param message           协议中立入站消息
     * @return 入库结果；账号不匹配时 {@code accepted=false} 且 {@code created=false}
     */
    public IngestResult ingest(String expectedAccountId, InboundMessage message) {
        String expected = requireAccountId(expectedAccountId);
        Objects.requireNonNull(message, "message");
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("inbound"))) {
            return ingestScoped(expected, message);
        }
    }

    private IngestResult ingestScoped(String expectedAccountId, InboundMessage message) {
        ChatSourceKey source = sourceOf(expectedAccountId, message);
        if (!expectedAccountId.equals(message.selfId())) {
            OpsLog.warn("event=inbound_account_mismatch expectedAccountId={} "
                            + "actualAccountId={} adapterId={} messageKey={} "
                            + "outcome=rejected",
                    expectedAccountId, message.selfId(), message.adapterId(),
                    message.messageId());
            ChatMessage candidate = candidate(source, message);
            return new IngestResult(candidate, false, false);
        }
        LOG.debug("event=inbound_received threadId={} messageKey={} chatType={} "
                        + "segments={} hasReply={}",
                source.threadId(), message.messageId(), source.chatType(),
                message.segments().size(), !message.replyToMessageId().isEmpty());
        ChatMessage candidate = candidate(source, message);
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

    private static ChatMessage candidate(ChatSourceKey source, InboundMessage message) {
        return ChatMessage.inbound(
                source,
                message.messageId(),
                message.userId(),
                message.senderName(),
                message.receivedAt(),
                message.receivedAt(),
                message.text(),
                message.nonTextSummary(),
                message.replyToMessageId());
    }

    private static ChatSourceKey sourceOf(String accountId, InboundMessage message) {
        if (message.sourceAddress() != null) {
            if (!accountId.equals(message.sourceAddress().accountId())) {
                throw new IllegalArgumentException(
                        "inbound source accountId 与绑定账号不一致");
            }
            return ChatSourceKey.fromSourceAddress(message.sourceAddress());
        }
        return switch (message.sessionType()) {
            case PRIVATE -> ChatSourceKey.privateChat(accountId, message.userId());
            case GROUP -> ChatSourceKey.groupChat(accountId, message.groupId());
            case EXTERNAL -> throw new IllegalArgumentException(
                    "EXTERNAL 消息必须携带 sourceAddress");
        };
    }

    private static String requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("expectedAccountId 不能为空");
        }
        return accountId.trim();
    }

    /** 调度器入口；delay=0 表示使用默认防抖窗口。 */
    @FunctionalInterface
    public interface DigestTrigger {
        void enqueue(String threadId, long delayMillis);
    }

    /** 入库结果；账号校验不通过时 accepted=false 且消息不会写入消息板。 */
    public record IngestResult(ChatMessage message, boolean created, boolean accepted) {
        public IngestResult {
            if (message == null) {
                throw new IllegalArgumentException("message 不能为空");
            }
            if (created && !accepted) {
                throw new IllegalArgumentException("created 结果必须 accepted");
            }
        }

        /** 兼容旧调用：两参结果默认表示账号校验通过。 */
        public IngestResult(ChatMessage message, boolean created) {
            this(message, created, true);
        }
    }
}
