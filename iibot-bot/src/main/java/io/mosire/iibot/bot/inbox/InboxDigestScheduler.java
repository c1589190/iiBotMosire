package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.connect.ops.OpsLog;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 按 thread 防抖的 Inbox digest 调度器。
 *
 * <p>同一 thread 在窗口内多次 enqueue 只保留最后一次；不同 thread 独立计时。
 * 调度线程只做 flush，不直接把异常抛给 adapter 回调。</p>
 */
public final class InboxDigestScheduler implements AutoCloseable {

    private final InboxDigestService service;
    private final long debounceMillis;
    private final Consumer<Throwable> errorHandler;
    private final ScheduledExecutorService executor;
    private final Map<String, Long> generations = new HashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicReference<Throwable> lastError = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public InboxDigestScheduler(InboxDigestService service, Duration debounce) {
        this(service, debounce, failure -> { });
    }

    public InboxDigestScheduler(InboxDigestService service, Duration debounce,
                                Consumer<Throwable> errorHandler) {
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(debounce, "debounce");
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce 不能为负");
        }
        this.debounceMillis = debounce.toMillis();
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iibot-inbox-digest-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 登记 thread 的待 flush 任务；窗口内重复登记会重置计时。 */
    public synchronized void enqueue(String threadId) {
        enqueue(threadId, debounceMillis);
    }

    /** 使用来源权重策略给出的延迟登记 flush。 */
    public synchronized void enqueue(String threadId, long delayMillis) {
        requireThread(threadId);
        if (closed.get()) {
            throw new IllegalStateException("InboxDigestScheduler 已关闭");
        }
        long boundedDelay = Math.max(0L, Math.min(86_400_000L, delayMillis));
        long generation = nextGeneration.incrementAndGet();
        generations.put(threadId, generation);
        OpsLog.debug("event=inbox_digest_enqueue threadId={} delayMs={}",
                threadId, boundedDelay);
        executor.schedule(() -> runScheduled(threadId, generation),
                boundedDelay, TimeUnit.MILLISECONDS);
    }

    /** 立即 flush 一个 thread；取消尚未触发的防抖任务。 */
    public void flushNow(String threadId) {
        requireThread(threadId);
        synchronized (this) {
            nextGeneration.incrementAndGet();
            generations.remove(threadId);
        }
        flushSafely(threadId);
    }

    /** 最近一次后台 flush 的异常；成功不覆盖历史错误。 */
    public Throwable lastError() {
        return lastError.get();
    }

    /** 尚未触发的 thread 数；用于诊断。 */
    public synchronized int pendingCount() {
        return generations.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            generations.clear();
        }
        executor.shutdownNow();
        OpsLog.info("event=inbox_digest_scheduler_stopped");
    }

    private void runScheduled(String threadId, long generation) {
        synchronized (this) {
            Long current = generations.get(threadId);
            if (current == null || current.longValue() != generation) {
                return;
            }
            generations.remove(threadId);
        }
        flushSafely(threadId);
    }

    private void flushSafely(String threadId) {
        try {
            service.flushThread(threadId);
        } catch (Throwable failure) {
            lastError.set(failure);
            OpsLog.error("event=inbox_digest_failed threadId={} errorType={}",
                    threadId, failure.getClass().getSimpleName());
            try {
                errorHandler.accept(failure);
            } catch (RuntimeException ignored) {
                // 错误处理本身失败不能杀死调度线程。
            }
        }
    }

    private static void requireThread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
    }
}
