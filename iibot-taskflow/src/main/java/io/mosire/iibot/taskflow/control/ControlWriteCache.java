package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.tool.ToolResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 控制面写调用的结果缓存。
 *
 * <p>引擎本身已经按 {@code idempotencyKey} 兜底；本层再缓存一次，保证重复请求不会因
 * 参数重排、时钟推进或调用间状态变化而触发第二次领域写入口。缓存按幂等键全局唯一，
 * 与 {@code TaskflowEngine} 的口径一致：同一 key 不得被两个不同逻辑命令复用。</p>
 */
final class ControlWriteCache {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ToolResult> results = new HashMap<>();

    ToolResult execute(String idempotencyKey, Supplier<ToolResult> action) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(action, "action");
        lock.lock();
        try {
            ToolResult cached = results.get(idempotencyKey);
            if (cached != null) {
                return cached;
            }
            ToolResult result = action.get();
            results.put(idempotencyKey, result);
            return result;
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return results.size();
        } finally {
            lock.unlock();
        }
    }
}
