package io.mosire.iibot.taskflow.control;

import java.util.Map;

/**
 * 所有控制面写工具共同要求的三要素。
 *
 * <p>三要素由外部调用者提供，但 actor 不在这里，也不能从参数里选择：actor 在
 * {@link ExternalControlToolSet} 构造时由宿主绑定。{@code expectedRevision} 的具体
 * 聚合（全局版本或回合版本）由各工具在 schema 中说明。</p>
 */
record WriteEnvelope(long expectedRevision, String idempotencyKey, String reason) {

    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 512;

    static WriteEnvelope from(Map<String, Object> arguments) {
        long expectedRevision = ControlArguments.requiredNonNegativeLong(arguments, "expectedRevision");
        String idempotencyKey = ControlArguments.requiredText(arguments, "idempotencyKey");
        String reason = ControlArguments.requiredText(arguments, "reason");
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidControlArgumentException(
                    "参数 idempotencyKey 长度不能超过 " + MAX_KEY_LENGTH);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new InvalidControlArgumentException(
                    "参数 reason 长度不能超过 " + MAX_REASON_LENGTH);
        }
        return new WriteEnvelope(expectedRevision, idempotencyKey, reason);
    }
}
