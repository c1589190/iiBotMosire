package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;

/**
 * 适配器可观测事件。事件只携带已脱敏摘要，绝不包含协议原始 payload、token
 * 或完整 WebSocket/HTTP 查询串。
 *
 * @param type   事件类型
 * @param at     发生时间
 * @param detail 简短、已脱敏的说明
 */
public record AdapterEvent(Type type, Instant at, String detail) {

    /** 可观测事件类型。 */
    public enum Type {
        CONNECTED,
        DISCONNECTED,
        RECONNECT_SCHEDULED,
        HEARTBEAT,
        IGNORED_EVENT,
        ERROR
    }

    public AdapterEvent {
        type = Objects.requireNonNull(type, "type");
        at = Objects.requireNonNull(at, "at");
        detail = Objects.requireNonNull(detail, "detail");
    }
}
