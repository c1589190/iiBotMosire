package io.mosire.iibot.app.runtime;

import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.dispatch.InboundDispatcher;
import io.mosire.iibot.app.event.AgentLibEventSink;
import io.mosire.iibot.app.event.SqliteEventReadPort;
import io.mosire.iibot.app.event.TaskflowSnapshotStore;
import io.mosire.iibot.app.world.WorldDocs;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.agent.InteractionTaskflowService;
import io.mosire.iibot.taskflow.control.EventReadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已装配、可启动、可关闭的应用运行时。
 *
 * <p>启动顺序：adapter.start(dispatcher) → 自动冻结调度器。关闭顺序固定为 adapter、
 * dispatcher、scheduler、world docs、control plane、event read port、event sink；event sink
 * 内部关闭 SQLite 事件库。所有 close 幂等，单个组件异常不会阻断后续关闭。</p>
 *
 * <p><b>恢复定位</b>：领域状态由 {@link TaskflowSnapshotStore} 的完整快照恢复，事件库仍只做
 * 审计读取，不从事件逐条重放。快照与事件库最大 seq 的一致性由启动装配阶段严格校验。</p>
 */
public final class AppRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppRuntime.class);

    private final AppConfig config;
    private final TaskflowEngine engine;
    private final InteractionTaskflowService interactionService;
    private final MessagingAdapter adapter;
    private final InboundDispatcher dispatcher;
    private final OutboxSender outboxSender;
    private final AutoFreezeScheduler scheduler;
    private final AgentLibEventSink eventSink;
    private final SqliteEventReadPort eventReadPort;
    private final TaskflowSnapshotStore snapshotStore;
    private final WorldDocs worldDocs;
    private final ControlPlane controlPlane;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    AppRuntime(AppConfig config, TaskflowEngine engine,
               InteractionTaskflowService interactionService, MessagingAdapter adapter,
               InboundDispatcher dispatcher, OutboxSender outboxSender,
               AutoFreezeScheduler scheduler, AgentLibEventSink eventSink,
               SqliteEventReadPort eventReadPort, TaskflowSnapshotStore snapshotStore,
               WorldDocs worldDocs, ControlPlane controlPlane) {
        this.config = Objects.requireNonNull(config, "config");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.outboxSender = Objects.requireNonNull(outboxSender, "outboxSender");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.eventReadPort = Objects.requireNonNull(eventReadPort, "eventReadPort");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.worldDocs = Objects.requireNonNull(worldDocs, "worldDocs");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    }

    /** 启动 adapter 与自动冻结调度器；重复启动为空操作。 */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("运行时已关闭，不能再次启动");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        adapter.start(dispatcher);
        scheduler.start();
        controlPlane.start();
        LOG.info("应用运行时已启动 players={} directors={} worldVersion={}",
                engine.status().playerCount(), engine.status().directorCount(),
                engine.status().worldVersion());
    }

    /** 测试可同步提交一条入站消息；生产由 adapter 回调 {@link InboundDispatcher}。 */
    public boolean submit(InboundMessage message) {
        return dispatcher.submit(message);
    }

    /** 等待入站 mailbox 清空；用于受控测试与关停前观察。 */
    public boolean awaitIdle(java.time.Duration timeout) {
        return dispatcher.awaitIdle(timeout);
    }

    public AppConfig config() {
        return config;
    }

    public TaskflowEngine engine() {
        return engine;
    }

    public InteractionTaskflowService interactionService() {
        return interactionService;
    }

    public MessagingAdapter adapter() {
        return adapter;
    }

    public InboundDispatcher dispatcher() {
        return dispatcher;
    }

    public OutboxSender outboxSender() {
        return outboxSender;
    }

    public AutoFreezeScheduler scheduler() {
        return scheduler;
    }

    public EventReadPort eventReadPort() {
        return eventReadPort;
    }

    public WorldDocs worldDocs() {
        return worldDocs;
    }

    /** 当前世界事实的动态只读视图；每次读取都反映引擎最新状态。 */
    public List<WorldFact> worldFacts() {
        return engine.allWorldFacts();
    }

    /** Taskflow 完整状态快照文件路径（诊断用，不暴露快照正文）。 */
    public Path taskflowSnapshotPath() {
        return snapshotStore.path();
    }

    /** 按固定顺序幂等关闭；任何一步失败只记录，不中断后续释放。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeSafely("adapter", adapter::close);
        closeSafely("dispatcher", dispatcher::close);
        closeSafely("scheduler", scheduler::close);
        closeSafely("worldDocs", worldDocs::close);
        closeSafely("controlPlane", controlPlane::close);
        closeSafely("eventReadPort", eventReadPort::close);
        closeSafely("eventSink/eventStore", eventSink::close);
        LOG.info("应用运行时已关闭");
    }

    private static void closeSafely(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.warn("关闭组件失败 name={} error={}", name, e.getClass().getSimpleName());
        }
    }
}
