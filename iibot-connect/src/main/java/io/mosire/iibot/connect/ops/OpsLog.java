package io.mosire.iibot.connect.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运维事件日志门面。
 *
 * <p>所有 Bot 运行时事件统一写到 logger {@code iibot.ops}，由 log4j2 配置路由到
 * 独立滚动文件。调用方只传 ID、状态、错误码和计数；禁止传聊天正文、token、
 * Authorization 或完整协议载荷。</p>
 *
 * <p>当调用线程存在 {@link LogContext} 关联值时，门面会把 {@code traceId}、
 * {@code callId}、{@code bootId} 作为追加字段写入同一行，便于 grep；缺失时保持
 * 原消息不变。所有日志调用都是旁路：实现异常不会改变业务结果。</p>
 *
 * <p>{@link #debug} 只作为 debug-only 运维细节保留；正式运维事件必须使用
 * info/warn/error。</p>
 */
public final class OpsLog {

    public static final String LOGGER_NAME = "iibot.ops";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private OpsLog() {
    }

    public static void debug(String message, Object... arguments) {
        emit(() -> LOG.debug(decorate(message), arguments));
    }

    public static void info(String message, Object... arguments) {
        emit(() -> LOG.info(decorate(message), arguments));
    }

    public static void warn(String message, Object... arguments) {
        emit(() -> LOG.warn(decorate(message), arguments));
    }

    public static void error(String message, Object... arguments) {
        emit(() -> LOG.error(decorate(message), arguments));
    }

    /**
     * 统一旁路保护；包级可见以便自动化测试锁定“日志异常不影响业务”。
     */
    static void emit(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            // 运维日志是旁路：日志实现异常不得改变业务结果。
        }
    }

    private static String decorate(String message) {
        try {
            StringBuilder decorated = new StringBuilder(message == null ? "" : message);
            appendContext(decorated, message, LogContext.TRACE_ID, LogContext.currentTraceId());
            appendContext(decorated, message, LogContext.CALL_ID, LogContext.currentCallId());
            appendContext(decorated, message, LogContext.BOOT_ID, LogContext.currentBootId());
            return decorated.toString();
        } catch (RuntimeException ignored) {
            // 关联字段是增强信息；任何异常都退回原始消息。
            return message;
        }
    }

    private static void appendContext(StringBuilder decorated, String message,
                                      String key, String value) {
        String safe = safeValue(value);
        if (safe == null || (message != null && message.contains(key + "="))) {
            return;
        }
        if (!decorated.isEmpty()) {
            decorated.append(' ');
        }
        decorated.append(key).append('=').append(safe);
    }

    private static String safeValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String safe = trimmed.replaceAll("[\\r\\n\\t\\s]", "_");
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }
}
