package io.mosire.iibot.app.dispatch;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.SendOutcome;
import io.mosire.iibot.app.delivery.TurnResultDistributor;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.agent.InteractionReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入站消息调度器：按用户串行、不同用户可并发，NapCat WebSocket listener 只做入队。
 *
 * <p>实现方式：每个 userId 一个 mailbox；mailbox 在空闲时把一条 drain 任务提交到虚拟线程
 * executor。drain 循环按 FIFO 取消息顺序执行，因此同一用户天然串行；不同用户各自有独立的
 * drain 任务，可以并发。队列有界（{@code maxPendingPerUser}），并发会话数有界
 * （{@code maxConcurrentSessions}），满时记录并拒绝，不无限积压。</p>
 *
 * <p>处理器异常被捕获并记录脱敏日志，不会传播到 adapter 连接线程；同一用户后续消息仍会继续。
 * 应用层按 {@code dedupeKey} 再做一次防重入，重复消息在入队前即被忽略。处理完成后统一交给
 * {@link OutboxSender}，taskflow 不直接发送。</p>
 *
 * <p>若配置了 {@link AckPolicy}，首次通过 dedupe 的已登记私信会在独立虚拟线程中经 outbox 发送
 * ACK。adapter 回调仍只做入队，不等待发送回执；正式回复发送前会等待该 ACK 任务结束，
 * 以保证 ACK 先于正式回复，同时 ACK 的失败/UNKNOWN 不会改变正式处理结果，也绝不自动重发。
 * ACK 正文不写入日志、事件或 LLM 上下文。</p>
 */
public final class InboundDispatcher implements InboundHandler, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InboundDispatcher.class);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final InboundProcessor processor;
    private final OutboxSender outbox;
    private final TurnResultDistributor resultDistributor;
    private final AckPolicy ackPolicy;
    private final int maxConcurrentSessions;
    private final int maxPendingPerUser;
    private final ExecutorService executor;
    private final ExecutorService ackExecutor;
    private final DedupeCache dedupe;
    private final ConcurrentMap<String, Mailbox> mailboxes = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> pendingAcks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public InboundDispatcher(InboundProcessor processor, OutboxSender outbox,
                             int maxConcurrentSessions, int maxPendingPerUser,
                             int dedupeCapacity) {
        this(processor, outbox, null, null, maxConcurrentSessions, maxPendingPerUser,
                dedupeCapacity);
    }

    /** 带回合结果分发的构造器；每次入站处理完成后尝试分发已发布回合结果。 */
    public InboundDispatcher(InboundProcessor processor, OutboxSender outbox,
                             TurnResultDistributor resultDistributor,
                             int maxConcurrentSessions, int maxPendingPerUser,
                             int dedupeCapacity) {
        this(processor, outbox, resultDistributor, null, maxConcurrentSessions,
                maxPendingPerUser, dedupeCapacity);
    }

    /** 带 ACK 策略的构造器；ACK 在独立虚拟线程中发送，不阻塞 adapter 回调。 */
    public InboundDispatcher(InboundProcessor processor, OutboxSender outbox,
                             AckPolicy ackPolicy,
                             int maxConcurrentSessions, int maxPendingPerUser,
                             int dedupeCapacity) {
        this(processor, outbox, null, ackPolicy, maxConcurrentSessions, maxPendingPerUser,
                dedupeCapacity);
    }

    /** 带 ACK 策略与回合结果分发的构造器。 */
    public InboundDispatcher(InboundProcessor processor, OutboxSender outbox,
                             TurnResultDistributor resultDistributor, AckPolicy ackPolicy,
                             int maxConcurrentSessions, int maxPendingPerUser,
                             int dedupeCapacity) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.resultDistributor = resultDistributor;
        this.ackPolicy = ackPolicy;
        if (maxConcurrentSessions <= 0 || maxPendingPerUser <= 0 || dedupeCapacity <= 0) {
            throw new IllegalArgumentException("dispatcher 上限必须为正");
        }
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.maxPendingPerUser = maxPendingPerUser;
        this.dedupe = new DedupeCache(dedupeCapacity);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("iibot-inbound-", 0).factory());
        this.ackExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("iibot-ack-", 0).factory());
    }

    /** ACK 策略放在尾部的兼容重载。 */
    public InboundDispatcher(InboundProcessor processor, OutboxSender outbox,
                             int maxConcurrentSessions, int maxPendingPerUser,
                             int dedupeCapacity, AckPolicy ackPolicy) {
        this(processor, outbox, null, ackPolicy, maxConcurrentSessions, maxPendingPerUser,
                dedupeCapacity);
    }

    /** ACK 策略放在尾部的兼容重载。 */
    public InboundDispatcher(InboundProcessor processor, OutboxSender outbox,
                             TurnResultDistributor resultDistributor,
                             int maxConcurrentSessions, int maxPendingPerUser,
                             int dedupeCapacity, AckPolicy ackPolicy) {
        this(processor, outbox, resultDistributor, ackPolicy, maxConcurrentSessions,
                maxPendingPerUser, dedupeCapacity);
    }

    /** Adapter 回调入口；只入队，不阻塞。 */
    @Override
    public void onMessage(InboundMessage message) {
        try {
            submit(message);
        } catch (RuntimeException unexpected) {
            LOG.warn("入站消息入队失败 error={}", unexpected.getClass().getSimpleName());
        }
    }

    /**
     * 尝试提交一条消息。
     *
     * @return true 表示已入队；false 表示重复/达到上限/已关闭/入参非法
     */
    public boolean submit(InboundMessage message) {
        if (message == null) {
            LOG.warn("拒绝 null 入站消息");
            return false;
        }
        if (closed.get()) {
            LOG.warn("调度器已关闭，拒绝入站消息 userId={}", maskUser(message.userId()));
            return false;
        }
        if (!dedupe.firstSeen(message.dedupeKey())) {
            LOG.info("重复入站 dedupeKey={} 已忽略", message.dedupeKey());
            return false;
        }
        Mailbox current = mailboxes.get(message.userId());
        if (current == null) {
            if (mailboxes.size() >= maxConcurrentSessions) {
                LOG.warn("并发会话达到上限 {}，拒绝 userId={}", maxConcurrentSessions,
                        maskUser(message.userId()));
                return false;
            }
            Mailbox created = new Mailbox(message.userId());
            Mailbox existing = mailboxes.putIfAbsent(message.userId(), created);
            current = existing == null ? created : existing;
        }
        Mailbox mailbox = current;
        synchronized (mailbox) {
            if (mailbox.queue.size() >= maxPendingPerUser) {
                LOG.warn("用户 mailbox 队列达到上限 {}，拒绝 userId={}", maxPendingPerUser,
                        maskUser(message.userId()));
                return false;
            }
            // 只有消息确实被接受后才启动 ACK；队列满/关闭竞态下不会产生孤立 ACK。
            CompletableFuture<Void> ackDone = startAckIfNeeded(message);
            mailbox.queue.addLast(new QueuedMessage(message, ackDone));
            pending.incrementAndGet();
            if (!mailbox.draining) {
                mailbox.draining = true;
                executor.execute(() -> drain(mailbox));
            }
        }
        return true;
    }

    private CompletableFuture<Void> startAckIfNeeded(InboundMessage message) {
        Optional<String> ackText;
        try {
            ackText = ackPolicy == null ? Optional.empty() : ackPolicy.ackFor(message);
        } catch (RuntimeException policyFailure) {
            // 策略异常按“无 ACK”处理，不阻止正式处理，也不打印消息正文。
            LOG.warn("ACK 策略解析异常已隔离 dedupeKey={} error={}",
                    message.dedupeKey(), policyFailure.getClass().getSimpleName());
            return CompletableFuture.completedFuture(null);
        }
        if (ackText == null || ackText.isEmpty() || ackText.orElseThrow().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String text = ackText.orElseThrow();
        CompletableFuture<Void> done = new CompletableFuture<>();
        pendingAcks.add(done);
        try {
            ackExecutor.execute(() -> sendAck(message, text, done));
        } catch (RuntimeException rejected) {
            // 关闭竞态/executor 拒绝时不重试，并让等待者立即继续。
            pendingAcks.remove(done);
            done.complete(null);
            LOG.warn("ACK 任务调度失败 dedupeKey={} error={}",
                    message.dedupeKey(), rejected.getClass().getSimpleName());
        }
        return done;
    }

    private void sendAck(InboundMessage message, String text, CompletableFuture<Void> done) {
        try {
            List<SendOutcome> outcomes = outbox.sendPrivateToUser(message.userId(),
                    List.of(text), "ack:" + message.dedupeKey());
            for (SendOutcome outcome : outcomes) {
                if (outcome != null && !outcome.sent()) {
                    // 只记录稳定键和状态；不记录 ACK 正文或用户消息正文。
                    LOG.warn("ACK 发送未成功 dedupeKey={} key={} state={} code={}",
                            message.dedupeKey(), outcome.idempotencyKey(), outcome.state(),
                            outcome.errorCode());
                }
            }
        } catch (Throwable sendFailure) {
            // outbox 已明确：失败/UNKNOWN 不自动重发，也不影响正式消息处理。
            LOG.warn("ACK 发送异常已隔离 dedupeKey={} error={}",
                    message.dedupeKey(), sendFailure.getClass().getSimpleName());
        } finally {
            pendingAcks.remove(done);
            done.complete(null);
        }
    }

    private void drain(Mailbox mailbox) {
        while (true) {
            QueuedMessage queued;
            synchronized (mailbox) {
                queued = mailbox.queue.pollFirst();
                if (queued == null) {
                    mailbox.draining = false;
                    if (mailbox.queue.isEmpty()) {
                        mailboxes.remove(mailbox.userId, mailbox);
                    }
                    mailbox.notifyAll();
                    return;
                }
            }
            InboundMessage message = queued.message();
            try {
                List<InteractionReply> replies = processor.handle(message);
                if (replies != null && !replies.isEmpty()) {
                    awaitAckBeforeReply(queued);
                    List<SendOutcome> outcomes = outbox.sendReplies(message, replies);
                    outcomes.stream().filter(SendOutcome::unknown).forEach(outcome ->
                            LOG.error("入站回复进入 UNKNOWN，需人工处置 dedupeKey={} key={}",
                                    message.dedupeKey(), outcome.idempotencyKey()));
                }
                if (resultDistributor != null) {
                    resultDistributor.distributeIfReady();
                }
            } catch (Throwable processingFailure) {
                // 只记录异常类型与稳定标识；不打印消息正文、堆栈或可能含密钥的异常消息。
                LOG.warn("入站处理异常已隔离 userId={} dedupeKey={} error={}",
                        maskUser(message.userId()), message.dedupeKey(),
                        processingFailure.getClass().getSimpleName());
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    /**
     * 正式回复前等待同一条消息的 ACK 任务结束，确保正常路径下出站顺序为 ACK → 回复。
     * ACK 任务只受 outbox 有界发送超时约束，因此不会无限阻塞；任务失败也已完成 future。
     */
    private void awaitAckBeforeReply(QueuedMessage queued) {
        CompletableFuture<Void> ackDone = queued.ackDone();
        if (ackDone == null || ackDone.isDone()) {
            return;
        }
        try {
            ackDone.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("等待 ACK 排序被中断 dedupeKey={}", queued.message().dedupeKey());
        } catch (ExecutionException impossible) {
            // sendAck 始终正常完成 future；此分支只做防御性记录。
            LOG.warn("ACK 任务异常完成 dedupeKey={}", queued.message().dedupeKey());
        }
    }

    /** 等待当前队列、在途处理与 ACK 任务全部清空；测试与受控关闭使用。超时返回 false。 */
    public boolean awaitIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0 || !pendingAcks.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 等待所有 ACK 发送任务结束；测试用。 */
    public boolean awaitAcks(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!pendingAcks.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 当前排队+在途任务数。 */
    public int pendingCount() {
        return pending.get();
    }

    /** 当前尚未结束的 ACK 任务数。 */
    public int pendingAckCount() {
        return pendingAcks.size();
    }

    public int sessionCount() {
        return mailboxes.size();
    }

    /** 幂等关闭；先在途任务有界等待，然后强制终止。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        awaitTermination(executor);
        mailboxes.values().forEach(mailbox -> {
            synchronized (mailbox) {
                mailbox.queue.clear();
                mailbox.notifyAll();
            }
        });
        mailboxes.clear();
        ackExecutor.shutdown();
        awaitTermination(ackExecutor);
        // executor 强制终止时可能有 ACK future 未跑到 finally；解除等待者并释放测试可见状态。
        pendingAcks.forEach(done -> done.complete(null));
        pendingAcks.clear();
    }

    private static void awaitTermination(ExecutorService pool) {
        try {
            if (!pool.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private static String maskUser(String userId) {
        if (userId == null || userId.length() <= 4) {
            return "<user>";
        }
        return "***" + userId.substring(userId.length() - 2);
    }

    private record QueuedMessage(InboundMessage message, CompletableFuture<Void> ackDone) {
        private QueuedMessage {
            Objects.requireNonNull(message, "message");
        }
    }

    private static final class Mailbox {
        private final String userId;
        private final ArrayDeque<QueuedMessage> queue = new ArrayDeque<>();
        private boolean draining;

        private Mailbox(String userId) {
            this.userId = userId;
        }
    }

    /** 有界 LRU 去重缓存；仅内存，重启后由 adapter 的 messageId 去重继续兜底。 */
    private static final class DedupeCache {
        private final int capacity;
        private final LinkedHashMap<String, Boolean> seen;

        private DedupeCache(int capacity) {
            this.capacity = capacity;
            this.seen = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DedupeCache.this.capacity;
                }
            };
        }

        private synchronized boolean firstSeen(String key) {
            if (key == null || key.isBlank()) {
                return false;
            }
            return seen.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }
}
