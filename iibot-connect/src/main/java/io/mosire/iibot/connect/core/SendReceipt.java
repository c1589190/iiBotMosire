package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 发送回执，明确区分三种外部副作用语义。
 *
 * <ul>
 *   <li>{@link State#SENT}：服务端明确接受，至少包含一个 adapter message id；</li>
 *   <li>{@link State#FAILED_BEFORE_SEND}：服务端明确拒绝或请求在发出前失败，
 *       可以确定没有产生新的消息；</li>
 *   <li>{@link State#UNKNOWN}：超时、IO 中断、响应不可解析或仅部分分段成功，
 *       消息可能已经发出，调用方不得盲目自动重试。</li>
 * </ul>
 *
 * @param state             三态结果
 * @param adapterMessageIds 已取得的外部消息 id；可能为空，UNKNOWN 时也可能非空
 * @param errorCode         失败/不确定时的稳定错误码；SENT 时为 null
 * @param detail            脱敏后的简短说明，不包含 token 或完整查询串
 * @param occurredAt        回执生成时间
 */
public record SendReceipt(
        State state,
        List<String> adapterMessageIds,
        String errorCode,
        String detail,
        Instant occurredAt) {

    /** 发送结果三态。 */
    public enum State {
        SENT,
        FAILED_BEFORE_SEND,
        UNKNOWN
    }

    public SendReceipt {
        state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(adapterMessageIds, "adapterMessageIds");
        adapterMessageIds = List.copyOf(adapterMessageIds);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (state == State.SENT && adapterMessageIds.isEmpty()) {
            throw new IllegalArgumentException("SENT 回执必须包含 adapter message id");
        }
        if (state != State.SENT && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("非 SENT 回执必须包含 errorCode");
        }
    }

    /** 构造成功回执。 */
    public static SendReceipt sent(List<String> messageIds) {
        return new SendReceipt(State.SENT, messageIds, null, "", Instant.now());
    }

    /** 构造明确失败回执。 */
    public static SendReceipt failed(String errorCode, String detail) {
        return new SendReceipt(State.FAILED_BEFORE_SEND, List.of(), errorCode, detail, Instant.now());
    }

    /** 构造不确定回执。 */
    public static SendReceipt unknown(String errorCode, String detail) {
        return unknown(errorCode, detail, List.of());
    }

    /** 构造可能已部分发送的不确定回执。 */
    public static SendReceipt unknown(String errorCode, String detail, List<String> messageIds) {
        return new SendReceipt(State.UNKNOWN, messageIds, errorCode, detail, Instant.now());
    }

    /** 是否为成功状态。 */
    public boolean isSent() {
        return state == State.SENT;
    }
}
