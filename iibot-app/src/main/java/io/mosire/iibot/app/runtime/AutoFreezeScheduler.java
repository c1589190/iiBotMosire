package io.mosire.iibot.app.runtime;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.agent.InteractionTaskflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动冻结调度器：周期性调用 {@link InteractionTaskflowService#pollExpiredTurn(Actor)}。
 *
 * <p>调度任务内部捕获所有 {@link Throwable} 并记录脱敏日志，任何单次异常都不会杀死进程或
 * 终止后续调度。真正冻结仍由任务流状态机与 expectedRevision CAS 完成，调度器不直接改状态。</p>
 */
public final class AutoFreezeScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AutoFreezeScheduler.class);

    private final InteractionTaskflowService service;
    private final Actor operator;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AutoFreezeScheduler(InteractionTaskflowService service, Actor operator,
                               Duration interval) {
        this.service = Objects.requireNonNull(service, "service");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval 必须为正");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iibot-auto-freeze");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动周期任务；重复调用为空操作。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor.scheduleAtFixedRate(this::runOnceSafely,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("自动冻结调度器已启动 interval={}s", interval.toSeconds());
    }

    /** 执行一次检查；测试可直接调用。异常不向外传播。 */
    public void runOnceSafely() {
        if (closed.get()) {
            return;
        }
        try {
            Optional<CommandResult> result = service.pollExpiredTurn(operator);
            result.ifPresent(commandResult -> {
                if (!commandResult.ok()) {
                    LOG.warn("自动冻结未成功 code={} message={}", commandResult.code(),
                            commandResult.message());
                } else {
                    LOG.info("自动冻结成功 turnState={} revision={}",
                            commandResult.turnState(), commandResult.revision());
                }
            });
        } catch (Throwable failure) {
            LOG.warn("自动冻结调度任务异常已隔离 error={}", failure.getClass().getSimpleName());
        }
    }

    /** 幂等关闭；不等待在途调度无限完成。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
    }
}
