package io.mosire.iibot.connect.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运维事件日志门面。
 *
 * <p>所有 Bot 运行时事件统一写到 logger {@code iibot.ops}，由 log4j2 配置路由到
 * 独立滚动文件。调用方只传 ID、状态、错误码和计数；禁止传聊天正文、token、
 * Authorization 或完整协议载荷。</p>
 */
public final class OpsLog {

    public static final String LOGGER_NAME = "iibot.ops";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private OpsLog() {
    }

    public static void debug(String message, Object... arguments) {
        emit(() -> LOG.debug(message, arguments));
    }

    public static void info(String message, Object... arguments) {
        emit(() -> LOG.info(message, arguments));
    }

    public static void warn(String message, Object... arguments) {
        emit(() -> LOG.warn(message, arguments));
    }

    public static void error(String message, Object... arguments) {
        emit(() -> LOG.error(message, arguments));
    }

    private static void emit(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            // 运维日志是旁路：日志实现异常不得改变业务结果。
        }
    }
}
