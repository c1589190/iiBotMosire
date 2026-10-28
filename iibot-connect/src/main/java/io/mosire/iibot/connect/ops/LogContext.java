package io.mosire.iibot.connect.ops;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 轻量级日志关联上下文：bootId、traceId、callId。
 *
 * <p>关联值写入 SLF4J {@link MDC}，由具体日志实现（本项目为 Log4j2）的
 * PatternLayout 或 {@link OpsLog}
 * 带入日志行。上下文严格按作用域恢复：{@link Scope#close()} 会把本线程恢复到进入
 * 作用域之前的值；进入前不存在的键会被移除，因此不会泄漏到后续任务。默认
 * MDC 是线程本地存储，不会自动继承到新线程；需要跨线程传递时，调用方
 * 必须显式在新线程内重新打开作用域。</p>
 *
 * <p>本类只生成/保存不透明 ID，不接触聊天正文、token 或协议载荷。</p>
 */
public final class LogContext {

    /** 一次入站、工具调用、出站或启动流程的关联 ID。 */
    public static final String TRACE_ID = "traceId";
    /** MCP 工具调用 ID；在同一 trace 内标识一次调用。 */
    public static final String CALL_ID = "callId";
    /** 进程/启动实例 ID。 */
    public static final String BOOT_ID = "bootId";

    private static final int MAX_ID_LENGTH = 96;
    private static final String RANDOM_ALPHABET = "0123456789abcdef";

    private LogContext() {
    }

    /** 生成一次启动关联 ID，例如 {@code boot-3f9a1c2d4e5f}。 */
    public static String newBootId() {
        return "boot-" + randomId();
    }

    /** 生成一个工具调用 ID，例如 {@code call-3f9a1c2d4e5f}。 */
    public static String newCallId() {
        return "call-" + randomId();
    }

    /**
     * 生成一个带类型前缀的 trace ID，例如 {@code inbound:3f9a1c2d4e5f}。
     *
     * @param kind 操作类型，只允许字母、数字、下划线、短横线；空值回退为 {@code op}
     */
    public static String newTraceId(String kind) {
        return normalizeKind(kind) + ":" + randomId();
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    public static String currentCallId() {
        return MDC.get(CALL_ID);
    }

    public static String currentBootId() {
        return MDC.get(BOOT_ID);
    }

    /** 只设置 bootId，保留已有 trace/call。 */
    public static Scope openBoot(String bootId) {
        return open(false, null, false, null, true, bootId);
    }

    /** 只设置 traceId，保留已有 call/boot，便于嵌套操作继承 callId。 */
    public static Scope openTrace(String traceId) {
        return open(true, traceId, false, null, false, null);
    }

    /** 同时设置 traceId 和 callId。 */
    public static Scope open(String traceId, String callId) {
        return open(true, traceId, true, callId, false, null);
    }

    /** 一次 MCP 工具调用作用域：traceId 与 callId 使用同一个随机后缀。 */
    public static Scope openToolCall() {
        String suffix = randomId();
        return open(true, "tool:" + suffix, true, "call-" + suffix, false, null);
    }

    /** 在 trace 作用域内执行并返回。 */
    public static <T> T call(String traceId, Supplier<T> action) {
        try (Scope ignored = openTrace(traceId)) {
            return action.get();
        }
    }

    /** 在 trace 作用域内执行。 */
    public static void run(String traceId, Runnable action) {
        try (Scope ignored = openTrace(traceId)) {
            action.run();
        }
    }

    /** 清理当前线程的全部关联键；只应在启动/测试边界使用。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(CALL_ID);
        MDC.remove(BOOT_ID);
    }

    private static Scope open(boolean setTrace, String traceId, boolean setCall,
                              String callId, boolean setBoot, String bootId) {
        String previousTrace = MDC.get(TRACE_ID);
        String previousCall = MDC.get(CALL_ID);
        String previousBoot = MDC.get(BOOT_ID);
        if (setTrace) {
            putOrRemove(TRACE_ID, traceId);
        }
        if (setCall) {
            putOrRemove(CALL_ID, callId);
        }
        if (setBoot) {
            putOrRemove(BOOT_ID, bootId);
        }
        String activeTrace = MDC.get(TRACE_ID);
        String activeCall = MDC.get(CALL_ID);
        return new Scope(previousTrace, previousCall, previousBoot, activeTrace, activeCall);
    }

    private static void putOrRemove(String key, String value) {
        String safe = sanitize(value);
        if (safe == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, safe);
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String safe = trimmed.replaceAll("[^A-Za-z0-9_.:@-]", "_");
        return safe.length() <= MAX_ID_LENGTH ? safe : safe.substring(0, MAX_ID_LENGTH);
    }

    private static String normalizeKind(String kind) {
        String safe = sanitize(kind);
        if (safe == null) {
            return "op";
        }
        return safe.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static String randomId() {
        StringBuilder out = new StringBuilder(12);
        UUID uuid = UUID.randomUUID();
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        for (int index = 0; index < 12; index++) {
            long value = index < 8 ? high : low;
            int shift = (index < 8 ? index : index - 8) * 8;
            int nibble = (int) ((value >>> shift) & 0xFF) & 0x0F;
            out.append(RANDOM_ALPHABET.charAt(nibble));
        }
        return out.toString();
    }

    /**
     * 一次关联作用域。关闭时恢复进入前的 trace/call/boot 值；可重复关闭。
     */
    public static final class Scope implements AutoCloseable {

        private final String previousTrace;
        private final String previousCall;
        private final String previousBoot;
        private final String traceId;
        private final String callId;
        private boolean closed;

        private Scope(String previousTrace, String previousCall, String previousBoot,
                      String traceId, String callId) {
            this.previousTrace = previousTrace;
            this.previousCall = previousCall;
            this.previousBoot = previousBoot;
            this.traceId = traceId;
            this.callId = callId;
        }

        public String traceId() {
            return traceId;
        }

        public String callId() {
            return callId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            putOrRemove(TRACE_ID, previousTrace);
            putOrRemove(CALL_ID, previousCall);
            putOrRemove(BOOT_ID, previousBoot);
        }
    }
}
