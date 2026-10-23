package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 适配器连接状态快照。
 *
 * @param state            连接状态
 * @param changedAt        最近一次状态变更时间
 * @param lastErrorCode    最近错误码；无错误时为 null
 * @param lastErrorMessage 已脱敏的最近错误说明；无错误时为 null
 */
public record AdapterStatus(
        ConnectionState state,
        Instant changedAt,
        String lastErrorCode,
        String lastErrorMessage) {

    /** 连接状态。 */
    public enum ConnectionState {
        STOPPED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        CLOSED
    }

    public AdapterStatus {
        state = Objects.requireNonNull(state, "state");
        changedAt = Objects.requireNonNull(changedAt, "changedAt");
    }

    /** 创建初始状态。 */
    public static AdapterStatus stopped() {
        return new AdapterStatus(ConnectionState.STOPPED, Instant.now(), null, null);
    }

    /** 连接状态；与 {@link #state()} 等价，便于调用方使用更明确的名称。 */
    public ConnectionState connectionState() {
        return state;
    }

    /** 最近错误码的可选视图。 */
    public Optional<String> optionalLastErrorCode() {
        return Optional.ofNullable(lastErrorCode);
    }

    /** 最近错误说明的可选视图。 */
    public Optional<String> optionalLastErrorMessage() {
        return Optional.ofNullable(lastErrorMessage);
    }
}
