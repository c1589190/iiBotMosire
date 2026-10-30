package io.mosire.iibot.app.delivery;

import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一出站发送适配器：把 bot core 的发送请求转换成 {@link OutboundMessage} 并交给
 * {@link MessagingAdapter}。
 *
 * <p><b>唯一外发出口</b>：上层不直接调用 adapter；私聊、群聊和富文本回复都经本类，
 * 共享最小发送间隔与三态回执语义。</p>
 *
 * <p><b>三态语义</b>：SENT 正常返回；FAILED_BEFORE_SEND 明确记录；UNKNOWN（超时、线程中断、
 * future 异常等）只记录，<b>绝不自动重发</b>。等待回执有上限，不会无限阻塞。</p>
 */
public final class OutboxSender {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxSender.class);

    private final MessagingAdapter adapter;
    private final Duration sendTimeout;
    private final String privateTempGroupId;
    private final long minSendIntervalNanos;
    private final Object paceLock = new Object();
    private long lastSendStartedNanos = -1L;

    public OutboxSender(MessagingAdapter adapter, Duration sendTimeout) {
        this(adapter, sendTimeout, null, Duration.ZERO);
    }

    public OutboxSender(MessagingAdapter adapter, Duration sendTimeout,
                        String privateTempGroupId) {
        this(adapter, sendTimeout, privateTempGroupId, Duration.ZERO);
    }

    /**
     * @param minSendInterval 两条逻辑消息之间的最小间隔；生产默认 150ms，
     *                        测试可用 ZERO 关闭
     */
    public OutboxSender(MessagingAdapter adapter, Duration sendTimeout,
                        String privateTempGroupId, Duration minSendInterval) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        this.privateTempGroupId = privateTempGroupId == null || privateTempGroupId.isBlank()
                ? null : privateTempGroupId;
        Objects.requireNonNull(minSendInterval, "minSendInterval");
        if (sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("sendTimeout 必须为正");
        }
        if (minSendInterval.isNegative()) {
            throw new IllegalArgumentException("minSendInterval 不能为负");
        }
        this.minSendIntervalNanos = minSendInterval.toNanos();
    }

    private OutboundMessage privateOutbound(String userId, String text, String key) {
        return privateOutbound(userId, privateTempGroupId, text, key);
    }

    private OutboundMessage privateOutbound(String userId, String temporaryGroupId,
                                            String text, String key) {
        return temporaryGroupId == null || temporaryGroupId.isBlank()
                ? OutboundMessage.privateMessage(userId, List.of(text), key)
                : OutboundMessage.privateTemporaryMessage(userId, temporaryGroupId,
                        List.of(text), key);
    }

    /** 使用配置的临时会话来源群发送富文本私聊回复。 */
    public List<SendOutcome> sendRichPrivateToUser(
            String userId, List<OutboundSegment> segments, String correlationKey) {
        return sendRichPrivateToUser(userId, privateTempGroupId, segments, correlationKey);
    }

    /**
     * 发送带 at/reply 段的富文本私聊回复。
     */
    public List<SendOutcome> sendRichPrivateToUser(
            String userId, String temporaryGroupId, List<OutboundSegment> segments,
            String correlationKey) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(correlationKey, "correlationKey");
        String key = "private:" + correlationKey + ":reply:0";
        List<OutboundSegment> normalized = normalizeRichSegments(segments);
        if (normalized.isEmpty()) {
            return List.of(new SendOutcome(0, key, SendReceipt.State.FAILED_BEFORE_SEND,
                    List.of(), "OUTBOX_BLANK_TEXT", "空白正文被拒绝（未发送）"));
        }
        OutboundMessage outbound = temporaryGroupId == null || temporaryGroupId.isBlank()
                ? OutboundMessage.richPrivateMessage(userId, normalized, key)
                : OutboundMessage.richPrivateTemporaryMessage(
                        userId, temporaryGroupId, normalized, key);
        return List.of(send(0, key, outbound));
    }

    /**
     * 发送带 at/reply 段的富文本群回复。
     */
    public List<SendOutcome> sendRichGroup(
            String groupId, List<OutboundSegment> segments, String correlationKey) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId 不能为空");
        }
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(correlationKey, "correlationKey");
        String key = "public:" + correlationKey + ":reply:0";
        List<OutboundSegment> normalized = normalizeRichSegments(segments);
        if (normalized.isEmpty()) {
            return List.of(new SendOutcome(0, key, SendReceipt.State.FAILED_BEFORE_SEND,
                    List.of(), "OUTBOX_BLANK_TEXT", "空白正文被拒绝（未发送）"));
        }
        OutboundMessage outbound = OutboundMessage.richGroupMessage(groupId, normalized, key);
        return List.of(send(0, key, outbound));
    }

    /**
     * 按统一 external source 发送；目标解析和平台映射由对应 Adapter 负责。
     */
    public List<SendOutcome> sendToSource(MessageSourceAddress sourceAddress,
                                          List<OutboundSegment> segments,
                                          String correlationKey) {
        Objects.requireNonNull(sourceAddress, "sourceAddress");
        Objects.requireNonNull(segments, "segments");
        if (correlationKey == null || correlationKey.isBlank()) {
            throw new IllegalArgumentException("correlationKey 不能为空");
        }
        String key = "external:" + correlationKey + ":reply:0";
        List<OutboundSegment> normalized = normalizeRichSegments(segments);
        if (normalized.isEmpty()) {
            return List.of(new SendOutcome(0, key, SendReceipt.State.FAILED_BEFORE_SEND,
                    List.of(), "OUTBOX_BLANK_TEXT", "空白正文被拒绝（未发送）"));
        }
        OutboundMessage outbound = OutboundMessage.richExternalMessage(
                sourceAddress, normalized, key);
        return List.of(send(0, key, outbound));
    }

    private static List<OutboundSegment> normalizeRichSegments(
            List<OutboundSegment> segments) {
        List<OutboundSegment> normalized = new ArrayList<>(segments.size());
        for (OutboundSegment segment : segments) {
            if (segment instanceof OutboundSegment.Text text) {
                String value = text.text();
                if (value != null && !value.isBlank()) {
                    normalized.add(new OutboundSegment.Text(value));
                }
            } else {
                normalized.add(segment);
            }
        }
        boolean hasContent = normalized.stream().anyMatch(segment ->
                segment instanceof OutboundSegment.Text
                        || segment instanceof OutboundSegment.Image
                        || segment instanceof OutboundSegment.Sticker);
        return hasContent ? List.copyOf(normalized) : List.of();
    }

    /**
     * 向指定私聊用户发送一条或多条正文，用于回合结果分发等宿主主动消息。
     *
     * @param userId         目标 QQ
     * @param texts          正文列表
     * @param correlationKey 稳定相关键；相同键重放会派生出相同幂等键
     * @return 与正文一一对应的发送结果
     */
    public List<SendOutcome> sendPrivateToUser(String userId, List<String> texts,
                                                String correlationKey) {
        return sendPrivateToUser(userId, privateTempGroupId, texts, correlationKey);
    }

    /**
     * 向指定私聊用户发送一条或多条正文，可显式指定群临时会话来源群。
     * 仅平台级 onboarding 等受控路径使用；普通回复仍走配置的 privateTempGroupId。
     */
    public List<SendOutcome> sendPrivateToUser(String userId, String temporaryGroupId,
                                                List<String> texts,
                                                String correlationKey) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        Objects.requireNonNull(texts, "texts");
        if (correlationKey == null || correlationKey.isBlank()) {
            throw new IllegalArgumentException("correlationKey 不能为空");
        }
        List<SendOutcome> outcomes = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String idempotencyKey = "private:" + correlationKey + ":reply:" + index;
            if (text == null || text.isBlank()) {
                outcomes.add(new SendOutcome(index, idempotencyKey,
                        SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                        "OUTBOX_BLANK_TEXT", "空白正文被拒绝（未发送）"));
                continue;
            }
            OutboundMessage outbound = privateOutbound(
                    userId, temporaryGroupId, text, idempotencyKey);
            outcomes.add(send(index, idempotencyKey, outbound));
        }
        return List.copyOf(outcomes);
    }

    /**
     * 公共群广播方法。幂等键由调用方提供的相关键派生，调用方必须保证同一批广播使用相同相关键。
     */
    public List<SendOutcome> broadcastToPublicGroup(String publicGroupId, List<String> texts,
                                                     String correlationKey) {
        if (publicGroupId == null || publicGroupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        Objects.requireNonNull(texts, "texts");
        if (correlationKey == null || correlationKey.isBlank()) {
            throw new IllegalArgumentException("correlationKey 不能为空");
        }
        List<SendOutcome> outcomes = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String idempotencyKey = "public:" + correlationKey + ":reply:" + index;
            if (text == null || text.isBlank()) {
                outcomes.add(new SendOutcome(index, idempotencyKey,
                        SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                        "OUTBOX_BLANK_TEXT", "空白正文被拒绝（未发送）"));
                continue;
            }
            OutboundMessage outbound = OutboundMessage.groupMessage(publicGroupId, List.of(text),
                    idempotencyKey);
            outcomes.add(send(index, idempotencyKey, outbound));
        }
        return List.copyOf(outcomes);
    }

    /**
     * 全局出站节奏：同一宿主的所有逻辑消息至少间隔 minSendInterval。
     * ACK 与正式回复来自不同线程，也必须共享同一个节奏锁。
     */
    private void awaitSendInterval() throws PacingInterruptedException {
        if (minSendIntervalNanos <= 0L) {
            return;
        }
        synchronized (paceLock) {
            long now = System.nanoTime();
            if (lastSendStartedNanos >= 0L) {
                long earliest = lastSendStartedNanos + minSendIntervalNanos;
                long remaining = earliest - now;
                if (remaining > 0L) {
                    try {
                        long millis = remaining / 1_000_000L;
                        int nanos = (int) (remaining % 1_000_000L);
                        Thread.sleep(millis, nanos);
                    } catch (InterruptedException interrupted) {
                        throw new PacingInterruptedException();
                    }
                }
            }
            lastSendStartedNanos = System.nanoTime();
        }
    }

    private SendOutcome send(int index, String idempotencyKey, OutboundMessage outbound) {
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("outbound"))) {
            return sendScoped(index, idempotencyKey, outbound);
        }
    }

    private SendOutcome sendScoped(int index, String idempotencyKey,
                                   OutboundMessage outbound) {
        try {
            awaitSendInterval();
        } catch (PacingInterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SendOutcome outcome = new SendOutcome(index, idempotencyKey,
                    SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                    "SEND_PACING_INTERRUPTED",
                    "发送节奏等待被中断；请求尚未发出");
            log(outcome);
            return outcome;
        }
        LOG.debug("event=outbound_route key={} targetType={} targetId={} segments={}",
                idempotencyKey, outbound.targetType(), outbound.targetId(),
                outbound.richSegments().isEmpty()
                        ? outbound.textSegments().size() : outbound.richSegments().size());
        try {
            SendReceipt receipt = adapter.send(outbound).toCompletableFuture()
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            SendOutcome outcome = new SendOutcome(index, idempotencyKey, receipt.state(),
                    receipt.adapterMessageIds(), receipt.errorCode(), receipt.detail());
            log(outcome);
            return outcome;
        } catch (TimeoutException timeout) {
            SendOutcome outcome = new SendOutcome(index, idempotencyKey,
                    SendReceipt.State.UNKNOWN, List.of(), "SEND_TIMEOUT",
                    "等待发送回执超时；消息可能已发出，禁止自动重发");
            log(outcome);
            return outcome;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SendOutcome outcome = new SendOutcome(index, idempotencyKey,
                    SendReceipt.State.UNKNOWN, List.of(), "SEND_INTERRUPTED",
                    "发送等待被中断；消息可能已发出，禁止自动重发");
            log(outcome);
            return outcome;
        } catch (ExecutionException execution) {
            SendOutcome outcome = new SendOutcome(index, idempotencyKey,
                    SendReceipt.State.UNKNOWN, List.of(), "SEND_FUTURE_FAILED",
                    "发送 future 异常；无法确认是否已发出，禁止自动重发");
            log(outcome);
            return outcome;
        } catch (IllegalArgumentException invalid) {
            // 构造/调用参数非法属于发送前失败，明确可重试由人工决定。
            SendOutcome outcome = new SendOutcome(index, idempotencyKey,
                    SendReceipt.State.FAILED_BEFORE_SEND, List.of(), "SEND_INVALID_REQUEST",
                    "发送请求在发出前被拒绝");
            log(outcome);
            return outcome;
        } catch (RuntimeException unexpected) {
            SendOutcome outcome = new SendOutcome(index, idempotencyKey,
                    SendReceipt.State.UNKNOWN, List.of(), "SEND_UNEXPECTED",
                    "发送过程异常；无法确认是否已发出，禁止自动重发");
            log(outcome);
            return outcome;
        }
    }

    private static final class PacingInterruptedException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static void log(SendOutcome outcome) {
        switch (outcome.state()) {
            case SENT -> LOG.info("outbox 发送成功 key={} ids={}", outcome.idempotencyKey(),
                    outcome.adapterMessageIds());
            case FAILED_BEFORE_SEND -> LOG.warn("outbox 明确失败 key={} code={} detail={}",
                    outcome.idempotencyKey(), outcome.errorCode(), outcome.detail());
            case UNKNOWN -> LOG.error("outbox 状态未知 key={} code={} detail={}",
                    outcome.idempotencyKey(), outcome.errorCode(), outcome.detail());
        }
        switch (outcome.state()) {
            case SENT -> OpsLog.info("event=outbound_send key={} state={} code={} "
                            + "messageIds={}", outcome.idempotencyKey(), outcome.state(),
                    outcome.errorCode() == null ? "" : outcome.errorCode(),
                    outcome.adapterMessageIds());
            case FAILED_BEFORE_SEND -> OpsLog.warn("event=outbound_send key={} state={} "
                            + "code={} messageIds={}", outcome.idempotencyKey(),
                    outcome.state(),
                    outcome.errorCode() == null ? "" : outcome.errorCode(),
                    outcome.adapterMessageIds());
            case UNKNOWN -> OpsLog.error("event=outbound_send key={} state={} code={} "
                            + "messageIds={} retry=forbidden", outcome.idempotencyKey(),
                    outcome.state(),
                    outcome.errorCode() == null ? "" : outcome.errorCode(),
                    outcome.adapterMessageIds());
        }
    }
}
