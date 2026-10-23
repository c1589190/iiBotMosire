package io.mosire.iibot.app.delivery;

import io.mosire.iibot.connect.core.SendReceipt;

import java.util.List;
import java.util.Objects;

/**
 * 一条 outbox 发送的最终可审计结果。
 *
 * @param replyIndex       回复在本次入站批次中的下标（从 0 起）
 * @param idempotencyKey   稳定幂等键
 * @param state            三态发送结果
 * @param adapterMessageIds 已取得的外部消息 id
 * @param errorCode        非 SENT 时的稳定错误码
 * @param detail           脱敏说明
 */
public record SendOutcome(
        int replyIndex,
        String idempotencyKey,
        SendReceipt.State state,
        List<String> adapterMessageIds,
        String errorCode,
        String detail) {

    public SendOutcome {
        if (replyIndex < 0) {
            throw new IllegalArgumentException("replyIndex 不能为负");
        }
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        state = Objects.requireNonNull(state, "state");
        adapterMessageIds = adapterMessageIds == null ? List.of() : List.copyOf(adapterMessageIds);
        detail = detail == null ? "" : detail;
    }

    /** 是否明确成功。 */
    public boolean sent() {
        return state == SendReceipt.State.SENT;
    }

    /** 是否处于“可能已发出”的不确定态；调用方不得自动重发。 */
    public boolean unknown() {
        return state == SendReceipt.State.UNKNOWN;
    }
}
