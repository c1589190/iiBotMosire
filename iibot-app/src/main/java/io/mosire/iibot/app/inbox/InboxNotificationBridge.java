package io.mosire.iibot.app.inbox;

import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.client.InboxClientException;
import io.mosire.inbox.client.InboxModels;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 宿主侧 Inbox 通知桥。
 *
 * <p>桥按固定周期扫描权威 Ticket/Post 状态，只推送 bounded summary 与索引引用。
 * 它不是 Agent 轮询，不读取或传输工单/推文正文。push 失败只记录稳定错误类型，
 * 不抛回领域操作；下一次扫描仍使用同一 dedupeKey 重试。</p>
 */
public final class InboxNotificationBridge implements AutoCloseable {

    public static final String CHANNEL_TICKET = "ticket";
    public static final String CHANNEL_PUBLICATION_REVIEW = "publication-review";

    private static final Logger LOG = LoggerFactory.getLogger(InboxNotificationBridge.class);
    private static final Set<String> SUPPORTED_CHANNELS =
            Set.of(CHANNEL_TICKET, CHANNEL_PUBLICATION_REVIEW);

    private final TicketService ticketService;
    private final PostService postService;
    private final PushPort pushPort;
    private final String inboxId;
    private final Set<String> channels;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final Set<String> successfulDedupeKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 可测试/可替换的 push 边界；实现不得对外暴露 raw token。 */
    @FunctionalInterface
    public interface PushPort {
        InboxModels.PushResult push(String inboxId, InboxModels.PushItem item) throws Exception;
    }

    public InboxNotificationBridge(
            TicketService ticketService,
            PostService postService,
            PushPort pushPort,
            String inboxId,
            Set<String> channels,
            Duration interval) {
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
        this.postService = Objects.requireNonNull(postService, "postService");
        this.pushPort = Objects.requireNonNull(pushPort, "pushPort");
        if (inboxId == null || inboxId.isBlank()) {
            throw new IllegalArgumentException("inboxId 不能为空");
        }
        this.inboxId = inboxId;
        this.channels = channels == null || channels.isEmpty()
                ? SUPPORTED_CHANNELS : Set.copyOf(channels);
        if (!SUPPORTED_CHANNELS.containsAll(this.channels)) {
            throw new IllegalArgumentException("通知桥只支持 channel: " + SUPPORTED_CHANNELS);
        }
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval 必须为正");
        }
        this.interval = interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iibot-inbox-notification-bridge");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 使用程序自有 MCP token 的 InboxClient 构造通知桥。 */
    public static InboxNotificationBridge forClient(
            TicketService ticketService,
            PostService postService,
            InboxClient inboxClient,
            String inboxId,
            Set<String> channels,
            Duration interval) {
        Objects.requireNonNull(inboxClient, "inboxClient");
        return new InboxNotificationBridge(ticketService, postService,
                inboxClient::push, inboxId, channels, interval);
    }

    /** 启动周期扫描；重复调用幂等。 */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("通知桥已关闭，不能重新启动");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::scanSafely,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 手动扫描一次。仅用于启动初始化和测试；正常生产由调度线程调用。
     *
     * @return 本次新确认的 item 数（服务端 duplicate=true 也视为已确认，不计新增）
     */
    public synchronized int scanOnce() {
        if (closed.get()) {
            return 0;
        }
        return scanTickets() + scanPendingPublications();
    }

    private int scanTickets() {
        if (!channelEnabled(CHANNEL_TICKET)) {
            return 0;
        }
        int pushed = 0;
        List<Ticket> tickets;
        try {
            tickets = ticketService.snapshot().tickets();
        } catch (RuntimeException failure) {
            LOG.warn("Inbox 通知桥读取工单快照失败 type={}", failure.getClass().getSimpleName());
            return 0;
        }
        for (Ticket ticket : tickets) {
            if (!ticketPending(ticket.status())) {
                continue;
            }
            String dedupeKey = "ticket:" + ticket.ticketId() + ":r" + ticket.revision();
            if (successfulDedupeKeys.contains(dedupeKey)) {
                continue;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("ticketId", ticket.ticketId());
            content.put("type", ticket.type().name());
            content.put("status", ticket.status().name());
            content.put("revision", ticket.revision());
            InboxModels.PushItem item = InboxModels.PushItem
                    .builder(CHANNEL_TICKET)
                    .kind("ticket")
                    .summary(ticket.type().name())
                    .content(content)
                    .dedupeKey(dedupeKey)
                    .build();
            if (push(dedupeKey, item)) {
                pushed++;
            }
        }
        return pushed;
    }

    private int scanPendingPublications() {
        if (!channelEnabled(CHANNEL_PUBLICATION_REVIEW)) {
            return 0;
        }
        int pushed = 0;
        List<Post> posts;
        try {
            posts = postService.snapshot().posts();
        } catch (RuntimeException failure) {
            LOG.warn("Inbox 通知桥读取推文快照失败 type={}", failure.getClass().getSimpleName());
            return 0;
        }
        for (Post post : posts) {
            if (post.status() != PostStatus.PENDING_APPROVAL) {
                continue;
            }
            String dedupeKey = "post-review:" + post.postId() + ":r" + post.revision();
            if (successfulDedupeKeys.contains(dedupeKey)) {
                continue;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("postId", post.postId());
            content.put("visibility", post.visibility().name());
            content.put("revision", post.revision());
            InboxModels.PushItem item = InboxModels.PushItem
                    .builder(CHANNEL_PUBLICATION_REVIEW)
                    .kind("review")
                    .summary(post.visibility().name())
                    .content(content)
                    .dedupeKey(dedupeKey)
                    .build();
            if (push(dedupeKey, item)) {
                pushed++;
            }
        }
        return pushed;
    }

    private boolean push(String dedupeKey, InboxModels.PushItem item) {
        try {
            InboxModels.PushResult result = pushPort.push(inboxId, item);
            if (result == null) {
                LOG.warn("Inbox 通知桥 push 返回空结果 channel={} errorType=EMPTY_RESULT",
                        item.channel());
                return false;
            }
            successfulDedupeKeys.add(dedupeKey);
            return !result.duplicate();
        } catch (InboxClientException failure) {
            LOG.warn("Inbox 通知桥 push 失败 channel={} errorType={}",
                    item.channel(), stableErrorType(failure));
            return false;
        } catch (Exception failure) {
            LOG.warn("Inbox 通知桥 push 失败 channel={} errorType={}",
                    item.channel(), stableErrorType(failure));
            return false;
        }
    }

    private void scanSafely() {
        try {
            scanOnce();
        } catch (RuntimeException failure) {
            LOG.warn("Inbox 通知桥扫描失败 type={}", failure.getClass().getSimpleName());
        }
    }

    private boolean channelEnabled(String channel) {
        return channels.contains(channel);
    }

    private static boolean ticketPending(TicketStatus status) {
        return status == TicketStatus.OPEN
                || status == TicketStatus.CLAIMED
                || status == TicketStatus.CONFIRMED;
    }

    private static String stableErrorType(Throwable failure) {
        if (failure instanceof InboxClientException clientFailure) {
            String code = clientFailure.code();
            return code == null || code.isBlank() ? "INBOX_CLIENT_ERROR" : code;
        }
        return failure.getClass().getSimpleName();
    }

    public Set<String> supportedChannels() {
        return SUPPORTED_CHANNELS;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        successfulDedupeKeys.clear();
    }
}
