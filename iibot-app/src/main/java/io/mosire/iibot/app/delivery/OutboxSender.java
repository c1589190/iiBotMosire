package io.mosire.iibot.app.delivery;

import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.agent.InteractionReply;
import io.mosire.iibot.taskflow.agent.PlayerFacingTextGuard;
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
 * 把任务流返回的 {@link InteractionReply} 转成 {@link OutboundMessage} 并通过
 * {@link MessagingAdapter} 发送。
 *
 * <p><b>唯一外发出口</b>：taskflow/agent 不直接调用 adapter；所有回复正文都经本类。私聊回复
 * 回到原 userId；群聊当前 service 返回空，但 {@link #broadcastToPublicGroup} 保留公共群广播
 * 方法。每条发送使用稳定幂等键 {@code inbound:<dedupeKey>:reply:<index>}。</p>
 *
 * <p><b>三态语义</b>：SENT 正常返回；FAILED_BEFORE_SEND 明确记录；UNKNOWN（超时、线程中断、
 * future 异常等）只记录，<b>绝不自动重发</b>。等待回执有上限，不会无限阻塞。</p>
 */
public final class OutboxSender {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxSender.class);

    private final MessagingAdapter adapter;
    private final Duration sendTimeout;
    private final String privateTempGroupId;

    public OutboxSender(MessagingAdapter adapter, Duration sendTimeout) {
        this(adapter, sendTimeout, null);
    }

    public OutboxSender(MessagingAdapter adapter, Duration sendTimeout,
                        String privateTempGroupId) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        this.privateTempGroupId = privateTempGroupId == null || privateTempGroupId.isBlank()
                ? null : privateTempGroupId;
        if (sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("sendTimeout 必须为正");
        }
    }

    private OutboundMessage privateOutbound(String userId, String text, String key) {
        return privateTempGroupId == null
                ? OutboundMessage.privateMessage(userId, List.of(text), key)
                : OutboundMessage.privateTemporaryMessage(userId, privateTempGroupId,
                        List.of(text), key);
    }

    /**
     * 发送一次入站处理产生的全部回复。
     *
     * @return 与 replies 一一对应的结果；空回复返回空列表
     */
    public List<SendOutcome> sendReplies(InboundMessage inbound, List<InteractionReply> replies) {
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(replies, "replies");
        List<SendOutcome> outcomes = new ArrayList<>(replies.size());
        for (int index = 0; index < replies.size(); index++) {
            InteractionReply reply = replies.get(index);
            if (reply == null) {
                continue;
            }
            String idempotencyKey = "inbound:" + inbound.dedupeKey() + ":reply:" + index;
            String playerText = inbound.sessionType() == InboundMessage.SessionType.PRIVATE
                    ? PlayerFacingTextGuard.normalizeForChat(reply.text())
                    : PlayerFacingTextGuard.normalizeForPost(reply.text());
            OutboundMessage outbound;
            try {
                outbound = switch (inbound.sessionType()) {
                    case PRIVATE -> privateOutbound(inbound.userId(), playerText,
                            idempotencyKey);
                    case GROUP -> OutboundMessage.groupMessage(
                            inbound.groupId() == null ? inbound.userId() : inbound.groupId(),
                            List.of(playerText), idempotencyKey);
                };
            } catch (RuntimeException invalid) {
                outcomes.add(new SendOutcome(index, idempotencyKey,
                        SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                        "OUTBOX_INVALID_MESSAGE", "回复无法构造成发送请求（未发送）"));
                continue;
            }
            outcomes.add(send(index, idempotencyKey, outbound));
        }
        return List.copyOf(outcomes);
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
            text = text == null ? null : PlayerFacingTextGuard.normalizeForChat(text);
            String idempotencyKey = "private:" + correlationKey + ":reply:" + index;
            if (text == null || text.isBlank()) {
                outcomes.add(new SendOutcome(index, idempotencyKey,
                        SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                        "OUTBOX_BLANK_TEXT", "空白正文被拒绝（未发送）"));
                continue;
            }
            OutboundMessage outbound = privateOutbound(userId, text, idempotencyKey);
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
            text = text == null ? null : PlayerFacingTextGuard.normalizeForPost(text);
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

    private SendOutcome send(int index, String idempotencyKey, OutboundMessage outbound) {
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

    private static void log(SendOutcome outcome) {
        switch (outcome.state()) {
            case SENT -> LOG.info("outbox 发送成功 key={} ids={}", outcome.idempotencyKey(),
                    outcome.adapterMessageIds());
            case FAILED_BEFORE_SEND -> LOG.warn("outbox 明确失败 key={} code={} detail={}",
                    outcome.idempotencyKey(), outcome.errorCode(), outcome.detail());
            case UNKNOWN -> LOG.error("outbox 状态未知 key={} code={} detail={}",
                    outcome.idempotencyKey(), outcome.errorCode(), outcome.detail());
        }
    }
}
