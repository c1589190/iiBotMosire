package io.mosire.iibot.connect.video.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AdapterEvent;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bilibili 私信/评论会话 Adapter。
 *
 * <p>支持私信轮询/发送和评论回复通知轮询/发送。所有入站消息使用统一 source
 * 进入消息板，所有出站走 {@link MessagingAdapter#send}；评论和私信正文都不进入日志。</p>
 */
public final class BilibiliAdapter implements MessagingAdapter, AccountInfoProvider {

    private static final Logger LOG = LoggerFactory.getLogger(BilibiliAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DEFAULT_WRITE_COOLDOWN_MILLIS = 5_000L;

    private final BilibiliAdapterConfig config;
    private final BilibiliHttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<AdapterEventListener> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, SendReceipt> receipts = new ConcurrentHashMap<>();
    private final Map<String, SendReceipt> interactionReceipts = new ConcurrentHashMap<>();
    private final Map<Long, String> bvidByAid = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile InboundHandler inboundHandler;
    private volatile AdapterStatus status = AdapterStatus.stopped();
    private volatile BilibiliModels.AccountInfo self;
    private volatile long beginTsMicros;
    private volatile long lastCommentNotificationId;
    private volatile boolean commentCursorInitialized;
    private volatile LocalDate writeDay = LocalDate.now(ZoneOffset.UTC);
    private volatile int writesToday;
    private volatile long nextWriteAtMillis;

    public BilibiliAdapter(BilibiliAdapterConfig config) throws IOException {
        this(config, new BilibiliHttpClient(BilibiliCredential.load(config.credentialFile())));
    }

    BilibiliAdapter(BilibiliAdapterConfig config, BilibiliHttpClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String adapterId() {
        return "bilibili-" + config.accountId();
    }

    @Override
    public void start(InboundHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (closed.get()) throw new IllegalStateException("BilibiliAdapter 已关闭");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("BilibiliAdapter 已启动");
        }
        inboundHandler = handler;
        running.set(true);
        status = new AdapterStatus(AdapterStatus.ConnectionState.CONNECTING,
                Instant.now(), null, null);
        executor.submit(this::runLoop);
    }

    @Override
    public CompletionStage<SendReceipt> send(OutboundMessage message) {
        Objects.requireNonNull(message, "message");
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("BILI_ADAPTER_CLOSED", "B站 Adapter 已关闭"));
        }
        SendReceipt cached = receipts.get(message.idempotencyKey());
        if (cached != null) return CompletableFuture.completedFuture(cached);
        MessageSourceAddress source = message.sourceAddress();
        SendReceipt receipt;
        if (source != null && "bili".equals(source.family()) && "dm".equals(source.kind())) {
            receipt = sendPrivate(message);
        } else if (source != null && "bili".equals(source.family())
                && ("comment".equals(source.kind()) || "video".equals(source.kind()))) {
            receipt = sendComment(message);
        } else {
            receipt = SendReceipt.failed("BILI_UNSUPPORTED_SOURCE",
                    "BilibiliAdapter 不支持该出站 source");
        }
        receipts.putIfAbsent(message.idempotencyKey(), receipt);
        return CompletableFuture.completedFuture(receipt);
    }

    @Override
    public AdapterStatus status() {
        return status;
    }

    @Override
    public void addEventListener(AdapterEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeEventListener(AdapterEventListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    @Override
    public CompletionStage<AccountInfo> accountInfo() {
        try {
            BilibiliModels.AccountInfo current = self;
            if (current == null) {
                current = client.validateAccount();
                verifyConfiguredUid(current.mid());
                self = current;
            }
            return CompletableFuture.completedFuture(
                    new AccountInfo(config.accountId(), current.name()));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("B站账号登录校验失败"));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        executor.shutdownNow();
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // 关闭阶段保留主流程结果。
        }
        status = new AdapterStatus(AdapterStatus.ConnectionState.CLOSED,
                Instant.now(), null, null);
        emit(AdapterEvent.Type.DISCONNECTED, "Bilibili Adapter 已关闭");
    }

    public BilibiliAdapterConfig config() {
        return config;
    }

    /** 显式点赞；默认关闭写入时 fail-closed，同一 idempotencyKey 不重复请求。 */
    public SendReceipt likeVideo(String bvid, String idempotencyKey) {
        return interaction("like", bvid, idempotencyKey,
                video -> client.likeVideo(Long.toString(video.aid())));
    }

    /** 显式投币；count 只允许 1 或 2。 */
    public SendReceipt addCoin(String bvid, int count, String idempotencyKey) {
        if (count < 1 || count > 2) {
            return SendReceipt.failed("BILI_COIN_COUNT", "投币数量只允许 1 或 2");
        }
        return interaction("coin", bvid, idempotencyKey,
                video -> client.addCoin(Long.toString(video.aid()), count));
    }

    /** 显式收藏到默认收藏夹。 */
    public SendReceipt favoriteVideo(String bvid, String idempotencyKey) {
        return interaction("favorite", bvid, idempotencyKey,
                video -> client.favoriteVideo(Long.toString(video.aid())));
    }

    /** 显式关注视频 UP 主。 */
    public SendReceipt followUploader(String bvid, String idempotencyKey) {
        return interaction("follow", bvid, idempotencyKey, video -> {
            if (video.ownerMid().isBlank()) {
                throw new BilibiliHttpClient.BilibiliApiException(
                        "BILI_OWNER_MISSING", "B站视频缺少 UP 主 UID");
            }
            long mid;
            try {
                mid = Long.parseLong(video.ownerMid());
            } catch (NumberFormatException invalid) {
                throw new BilibiliHttpClient.BilibiliApiException(
                        "BILI_OWNER_INVALID", "B站视频 UP 主 UID 非法");
            }
            return client.followUser(mid);
        });
    }

    private SendReceipt interaction(String action, String bvid, String idempotencyKey,
                                    InteractionCall call) {
        if (bvid == null || bvid.isBlank()) {
            return SendReceipt.failed("BILI_INVALID_TARGET", "B站视频 BV 号不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return SendReceipt.failed("BILI_IDEMPOTENCY_REQUIRED",
                    "互动写入必须提供 idempotencyKey");
        }
        if (!config.writeEnabled()) {
            return SendReceipt.failed("BILI_WRITE_DISABLED", "B站写入未启用");
        }
        String cacheKey = action + ":" + idempotencyKey.trim();
        SendReceipt cached = interactionReceipts.get(cacheKey);
        if (cached != null) return cached;
        BilibiliModels.VideoInfo video;
        try {
            video = client.video(bvid.trim(), null, 1);
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            return cache(cacheKey, SendReceipt.failed(rejected.code(),
                    "B站视频信息读取失败"));
        } catch (RuntimeException failure) {
            return cache(cacheKey, SendReceipt.failed("BILI_VIDEO_LOOKUP_FAILED",
                    "B站视频信息读取失败"));
        }
        SendReceipt quota = acquireWriteSlot();
        if (quota != null) return cache(cacheKey, quota);
        try {
            BilibiliModels.SendResult result = call.run(video);
            if (!result.accepted()) {
                return cache(cacheKey, SendReceipt.failed("BILI_INTERACTION_REJECTED",
                        "B站互动接口未接受请求"));
            }
            String actionId = result.msgKey().isBlank()
                    ? action + "-" + video.bvid() : result.msgKey();
            OpsLog.info("event=bili_interaction_sent accountId={} action={} bvid={}",
                    config.accountId(), action, video.bvid());
            return cache(cacheKey, new SendReceipt(SendReceipt.State.SENT,
                    List.of(actionId), null, "", Instant.now()));
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            OpsLog.warn("event=bili_interaction_rejected accountId={} action={} bvid={} code={}",
                    config.accountId(), action, video.bvid(), rejected.code());
            return cache(cacheKey, SendReceipt.failed(rejected.code(),
                    "B站互动被明确拒绝"));
        } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
            OpsLog.error("event=bili_interaction_unknown accountId={} action={} bvid={} code={} "
                            + "retry=forbidden", config.accountId(), action, video.bvid(),
                    uncertain.code());
            return cache(cacheKey, SendReceipt.unknown(uncertain.code(),
                    "B站互动网络状态未知，禁止自动重发"));
        } catch (RuntimeException unexpected) {
            OpsLog.error("event=bili_interaction_unknown accountId={} action={} bvid={} reason={} "
                            + "retry=forbidden", config.accountId(), action, video.bvid(),
                    unexpected.getClass().getSimpleName());
            return cache(cacheKey, SendReceipt.unknown("BILI_INTERACTION_UNEXPECTED",
                    "B站互动异常，无法确认是否已生效"));
        }
    }

    private SendReceipt cache(String key, SendReceipt receipt) {
        interactionReceipts.putIfAbsent(key, receipt);
        return interactionReceipts.getOrDefault(key, receipt);
    }

    @FunctionalInterface
    private interface InteractionCall {
        BilibiliModels.SendResult run(BilibiliModels.VideoInfo video);
    }

    private SendReceipt sendPrivate(OutboundMessage message) {
        MessageSourceAddress source = message.sourceAddress();
        if (source == null || !"bili".equals(source.family()) || !"dm".equals(source.kind())) {
            return SendReceipt.failed("BILI_UNSUPPORTED_SOURCE",
                    "BilibiliAdapter 当前只支持 bili_dm 出站");
        }
        if (!config.writeEnabled()) {
            return SendReceipt.failed("BILI_WRITE_DISABLED",
                    "B站写入未启用");
        }
        List<OutboundSegment> rich = message.richSegments();
        if (rich.stream().anyMatch(segment ->
                !(segment instanceof OutboundSegment.Text)
                        && !(segment instanceof OutboundSegment.Reply))) {
            return SendReceipt.failed("BILI_UNSUPPORTED_ATTACHMENT",
                    "B站私信当前只支持纯文本");
        }
        String text = extractText(message);
        if (text.isBlank()) {
            return SendReceipt.failed("OUTBOX_BLANK_TEXT", "空白正文被拒绝");
        }
        if (text.length() > 1000) {
            return SendReceipt.failed("BILI_TEXT_TOO_LONG", "B站私信正文最多 1000 字符");
        }
        long peer;
        try {
            peer = Long.parseLong(source.segments().get(1));
        } catch (RuntimeException invalid) {
            return SendReceipt.failed("BILI_INVALID_PEER", "B站私信对方 UID 非法");
        }
        SendReceipt quota = acquireWriteSlot();
        if (quota != null) return quota;
        try {
            BilibiliModels.SendResult result = client.sendPrivateMessage(peer, text);
            if (!result.accepted()) {
                return SendReceipt.failed("BILI_SEND_REJECTED",
                        "B站私信接口未接受请求");
            }
            String messageId = result.msgKey().isBlank()
                    ? "accepted-" + UUID.randomUUID() : result.msgKey();
            String detail = result.msgKey().isBlank()
                    ? "B站已接受请求但未返回 msg_key" : "";
            OpsLog.info("event=bili_dm_sent accountId={} peerUid={} accepted=true "
                    + "hasMsgKey={}", config.accountId(), peer, !result.msgKey().isBlank());
            return new SendReceipt(SendReceipt.State.SENT, List.of(messageId),
                    null, detail, Instant.now());
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            OpsLog.warn("event=bili_dm_send_rejected accountId={} peerUid={} code={}",
                    config.accountId(), peer, rejected.code());
            return SendReceipt.failed(rejected.code(), "B站私信发送被明确拒绝");
        } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
            OpsLog.error("event=bili_dm_send_unknown accountId={} peerUid={} code={} "
                            + "retry=forbidden", config.accountId(), peer, uncertain.code());
            return SendReceipt.unknown(uncertain.code(),
                    "B站私信发送网络状态未知，禁止自动重发");
        } catch (RuntimeException unexpected) {
            OpsLog.error("event=bili_dm_send_unknown accountId={} peerUid={} reason={} "
                            + "retry=forbidden", config.accountId(), peer,
                    unexpected.getClass().getSimpleName());
            return SendReceipt.unknown("BILI_SEND_UNEXPECTED",
                    "B站私信发送异常，无法确认是否已发出");
        }
    }

    private SendReceipt sendComment(OutboundMessage message) {
        MessageSourceAddress source = message.sourceAddress();
        if (!config.writeEnabled()) {
            return SendReceipt.failed("BILI_WRITE_DISABLED", "B站写入未启用");
        }
        if (message.richSegments().stream().anyMatch(segment ->
                !(segment instanceof OutboundSegment.Text)
                        && !(segment instanceof OutboundSegment.Reply))) {
            return SendReceipt.failed("BILI_UNSUPPORTED_ATTACHMENT",
                    "B站评论当前只支持纯文本");
        }
        String text = extractText(message);
        if (text.isBlank()) {
            return SendReceipt.failed("OUTBOX_BLANK_TEXT", "空白正文被拒绝");
        }
        if (text.length() > 1000) {
            return SendReceipt.failed("BILI_TEXT_TOO_LONG", "B站评论正文最多 1000 字符");
        }
        String bvid = source.segments().get(1);
        String rootRpid = "comment".equals(source.kind()) ? source.segments().get(2) : "";
        String replyTo = replyTarget(message);
        if (replyTo != null) {
            if ("video".equals(source.kind())) {
                return SendReceipt.failed("BILI_REPLY_ROOT_REQUIRED",
                        "回复 B站评论必须使用 bili_comment source 提供 root rpid");
            }
        }
        String parentRpid = replyTo == null || replyTo.isBlank() ? rootRpid : replyTo;
        String aid;
        try {
            aid = Long.toString(client.video(bvid, null, 1).aid());
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            return SendReceipt.failed(rejected.code(), "B站视频信息读取失败");
        } catch (RuntimeException failure) {
            return SendReceipt.failed("BILI_VIDEO_LOOKUP_FAILED", "B站视频信息读取失败");
        }
        SendReceipt quota = acquireWriteSlot();
        if (quota != null) return quota;
        try {
            BilibiliModels.SendResult result = client.replyToComment(
                    aid, rootRpid, parentRpid, text);
            if (!result.accepted()) {
                return SendReceipt.failed("BILI_COMMENT_REJECTED",
                        "B站评论接口未接受请求");
            }
            String messageId = result.msgKey().isBlank()
                    ? "accepted-" + UUID.randomUUID() : result.msgKey();
            OpsLog.info("event=bili_comment_sent accountId={} bvid={} hasRpid={}",
                    config.accountId(), bvid, !result.msgKey().isBlank());
            return new SendReceipt(SendReceipt.State.SENT, List.of(messageId),
                    null, result.msgKey().isBlank()
                    ? "B站已接受评论但未返回 rpid" : "", Instant.now());
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            OpsLog.warn("event=bili_comment_send_rejected accountId={} bvid={} code={}",
                    config.accountId(), bvid, rejected.code());
            return SendReceipt.failed(rejected.code(), "B站评论发送被明确拒绝");
        } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
            OpsLog.error("event=bili_comment_send_unknown accountId={} bvid={} code={} "
                            + "retry=forbidden", config.accountId(), bvid,
                    uncertain.code());
            return SendReceipt.unknown(uncertain.code(),
                    "B站评论发送网络状态未知，禁止自动重发");
        } catch (RuntimeException unexpected) {
            OpsLog.error("event=bili_comment_send_unknown accountId={} bvid={} reason={} "
                            + "retry=forbidden", config.accountId(), bvid,
                    unexpected.getClass().getSimpleName());
            return SendReceipt.unknown("BILI_COMMENT_SEND_UNEXPECTED",
                    "B站评论发送异常，无法确认是否已发出");
        }
    }

    private static String replyTarget(OutboundMessage message) {
        for (OutboundSegment segment : message.richSegments()) {
            if (segment instanceof OutboundSegment.Reply reply) {
                return reply.messageId();
            }
        }
        return "";
    }

    private SendReceipt acquireWriteSlot() {
        synchronized (writeLock) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(writeDay)) {
                writeDay = today;
                writesToday = 0;
            }
            long now = System.currentTimeMillis();
            if (now < nextWriteAtMillis) {
                return SendReceipt.failed("BILI_WRITE_COOLDOWN",
                        "B站写入冷却中");
            }
            if (writesToday >= config.maxWritesPerDay()) {
                return SendReceipt.failed("BILI_WRITE_QUOTA_EXCEEDED",
                        "B站每日写入额度已用完");
            }
            writesToday++;
            nextWriteAtMillis = now + DEFAULT_WRITE_COOLDOWN_MILLIS;
            return null;
        }
    }

    private static String extractText(OutboundMessage message) {
        if (message.richSegments().isEmpty()) {
            return String.join("\n", message.textSegments()).trim();
        }
        return message.richSegments().stream()
                .filter(OutboundSegment.Text.class::isInstance)
                .map(OutboundSegment.Text.class::cast)
                .map(OutboundSegment.Text::text)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .trim();
    }

    private void runLoop() {
        long backoffMillis = 1_000L;
        try {
            self = client.validateAccount();
            verifyConfiguredUid(self.mid());
            beginTsMicros = nowMicros();
            setStatus(AdapterStatus.ConnectionState.CONNECTED, null, null);
            emit(AdapterEvent.Type.CONNECTED, "Bilibili Adapter 已连接");
            OpsLog.info("event=bili_adapter_connected accountId={} uid={}",
                    config.accountId(), self.mid());
            while (running.get()) {
                try {
                    if (config.receivePrivateMessages()) {
                        pollPrivateMessages();
                    }
                    if (config.receiveCommentReplies()) {
                        pollCommentReplies();
                    }
                    backoffMillis = 1_000L;
                    sleepWithJitter();
                } catch (BilibiliHttpClient.BilibiliApiException rejected) {
                    if (isFatalAuth(rejected.code())) {
                        setStatus(AdapterStatus.ConnectionState.STOPPED,
                                rejected.code(), "B站登录态不可用");
                        OpsLog.error("event=bili_adapter_auth_failed accountId={} code={}",
                                config.accountId(), rejected.code());
                        running.set(false);
                        break;
                    }
                    setStatus(AdapterStatus.ConnectionState.RECONNECTING,
                            rejected.code(), "B站接口拒绝，等待退避");
                    emit(AdapterEvent.Type.ERROR, "B站接口拒绝: " + rejected.code());
                    sleepBackoff(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2L, 300_000L);
                } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
                    setStatus(AdapterStatus.ConnectionState.RECONNECTING,
                            uncertain.code(), "B站网络请求失败，等待退避");
                    emit(AdapterEvent.Type.RECONNECT_SCHEDULED,
                            "B站网络重连: " + uncertain.code());
                    sleepBackoff(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2L, 300_000L);
                } catch (RuntimeException unexpected) {
                    setStatus(AdapterStatus.ConnectionState.RECONNECTING,
                            "BILI_POLL_UNEXPECTED", "B站轮询异常，等待退避");
                    LOG.debug("event=bili_poll_failed accountId={} errorType={}",
                            config.accountId(), unexpected.getClass().getSimpleName());
                    sleepBackoff(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2L, 300_000L);
                }
            }
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            setStatus(AdapterStatus.ConnectionState.STOPPED,
                    rejected.code(), "B站登录校验失败");
            OpsLog.error("event=bili_adapter_start_failed accountId={} code={}",
                    config.accountId(), rejected.code());
        } catch (RuntimeException failure) {
            setStatus(AdapterStatus.ConnectionState.STOPPED,
                    "BILI_START_FAILED", "B站 Adapter 启动失败");
            OpsLog.error("event=bili_adapter_start_failed accountId={} reason={}",
                    config.accountId(), failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    void bindInboundHandlerForTest(InboundHandler handler) {
        this.inboundHandler = Objects.requireNonNull(handler, "handler");
        this.self = client.validateAccount();
        this.running.set(true);
    }

    void pollPrivateMessages() {
        BilibiliModels.SessionPage page = client.newSessions(beginTsMicros);
        boolean completed = true;
        for (BilibiliModels.PrivateSession session : page.sessions()) {
            if (!running.get()) return;
            if (session.unreadCount() <= 0L) continue;
            BilibiliModels.MessagePage messages = client.fetchSessionMessages(
                    session.talkerId(), session.sessionType(), session.ackSeqno(), 50);
            long ackThrough = session.ackSeqno();
            boolean handlerFailed = false;
            for (BilibiliModels.PrivateMessage message : messages.messages()) {
                if (message.msgSeqno() <= session.ackSeqno()) continue;
                if (message.senderUid() == self.mid()) {
                    ackThrough = Math.max(ackThrough, message.msgSeqno());
                    continue;
                }
                if (message.msgStatus() == 1 || message.msgStatus() == 2) {
                    ackThrough = Math.max(ackThrough, message.msgSeqno());
                    continue;
                }
                try {
                    inboundHandler.onMessage(toInbound(session, message));
                    ackThrough = Math.max(ackThrough, message.msgSeqno());
                } catch (RuntimeException failure) {
                    handlerFailed = true;
                    OpsLog.warn("event=bili_inbound_handler_failed accountId={} "
                                    + "talkerId={} errorType={}", config.accountId(),
                            session.talkerId(), failure.getClass().getSimpleName());
                    break;
                }
            }
            if (!handlerFailed && ackThrough > session.ackSeqno()) {
                client.updateAck(session.talkerId(), session.sessionType(), ackThrough);
            }
            if (handlerFailed) {
                completed = false;
            }
        }
        if (completed && page.nextBeginTsMicros() > beginTsMicros) {
            beginTsMicros = page.nextBeginTsMicros();
        }
    }

    void setCommentCursorForTest(long cursor) {
        this.lastCommentNotificationId = cursor;
        this.commentCursorInitialized = true;
    }

    void pollCommentReplies() {
        List<BilibiliModels.CommentNotification> notifications =
                client.commentNotifications(20);
        if (notifications.isEmpty()) return;
        long maxId = notifications.stream()
                .mapToLong(value -> parseLong(value.notificationId()))
                .max().orElse(0L);
        if (!commentCursorInitialized) {
            lastCommentNotificationId = maxId;
            commentCursorInitialized = true;
            return;
        }
        List<BilibiliModels.CommentNotification> ordered = new ArrayList<>(notifications);
        ordered.sort(java.util.Comparator.comparingLong(
                value -> parseLong(value.notificationId())));
        for (BilibiliModels.CommentNotification notification : ordered) {
            long notificationId = parseLong(notification.notificationId());
            if (notificationId > 0L && notificationId <= lastCommentNotificationId) {
                continue;
            }
            if (notification.businessId() != 1 || notification.subjectId() <= 0L) {
                if (notificationId > lastCommentNotificationId) {
                    lastCommentNotificationId = notificationId;
                }
                continue;
            }
            String bvid = bvidByAid.get(notification.subjectId());
            if (bvid == null || bvid.isBlank()) {
                BilibiliModels.VideoInfo video = client.video(
                        null, Long.toString(notification.subjectId()), 1);
                bvid = video.bvid();
                bvidByAid.put(notification.subjectId(), bvid);
            }
            try {
                inboundHandler.onMessage(toCommentInbound(notification, bvid));
                if (notificationId > lastCommentNotificationId) {
                    lastCommentNotificationId = notificationId;
                }
            } catch (RuntimeException failure) {
                OpsLog.warn("event=bili_comment_handler_failed accountId={} bvid={} "
                                + "errorType={}", config.accountId(), bvid,
                        failure.getClass().getSimpleName());
                break;
            }
        }
    }

    private InboundMessage toCommentInbound(
            BilibiliModels.CommentNotification notification, String bvid) {
        String messageId = notification.sourceRpid();
        String root = notification.rootRpid().isBlank()
                || "0".equals(notification.rootRpid())
                ? messageId : notification.rootRpid();
        MessageSourceAddress source = MessageSourceAddress.biliComment(
                config.accountId(), bvid, root);
        List<MessageSegment> segments = notification.message().isBlank()
                ? List.of() : List.of(MessageSegment.text(0, notification.message()));
        return new InboundMessage(
                adapterId(),
                config.accountId(),
                messageId,
                Long.toString(notification.userId()),
                notification.userName(),
                InboundMessage.SessionType.EXTERNAL,
                null,
                notification.message(),
                "",
                notification.targetRpid(),
                true,
                Instant.ofEpochSecond(Math.max(0L, notification.replyTimeSeconds())),
                "bili:comment:" + bvid + ":" + messageId,
                segments,
                source);
    }

    private InboundMessage toInbound(BilibiliModels.PrivateSession session,
                                     BilibiliModels.PrivateMessage message) {
        String text = "";
        String nonText = "";
        if (message.msgType() == 1) {
            text = extractContent(message.content());
            if (text.isBlank()) text = message.content();
        } else {
            nonText = nonTextSummary(message);
        }
        String messageId = message.msgKey().isBlank()
                ? session.talkerId() + ":" + message.msgSeqno()
                : message.msgKey();
        String senderName = session.talkerName().isBlank()
                ? Long.toString(message.senderUid()) : session.talkerName();
        MessageSourceAddress source = MessageSourceAddress.biliDm(
                config.accountId(), Long.toString(session.talkerId()));
        List<MessageSegment> segments = text.isBlank()
                ? List.of() : List.of(MessageSegment.text(0, text));
        return new InboundMessage(
                adapterId(),
                config.accountId(),
                messageId,
                Long.toString(message.senderUid()),
                senderName,
                InboundMessage.SessionType.EXTERNAL,
                null,
                text,
                nonText,
                "",
                false,
                Instant.ofEpochSecond(Math.max(0L, message.timestampSeconds())),
                "bili:dm:" + session.talkerId() + ":" + messageId,
                segments,
                source);
    }

    private static String extractContent(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(raw);
            JsonNode content = root.get("content");
            return content == null || content.isNull() ? "" : content.asText("");
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String nonTextSummary(BilibiliModels.PrivateMessage message) {
        return switch (message.msgType()) {
            case 2, 5 -> "[图片]";
            case 7 -> "[视频/卡片]";
            case 10 -> "[系统通知]";
            case 11 -> "[视频推送]";
            default -> "[B站消息类型 " + message.msgType() + "]";
        };
    }

    private void verifyConfiguredUid(long actualUid) {
        String configured = config.accountId();
        if (!configured.startsWith("bili-")) return;
        String suffix = configured.substring("bili-".length());
        try {
            long expected = Long.parseLong(suffix);
            if (expected != actualUid) {
                throw new BilibiliHttpClient.BilibiliApiException(
                        "BILI_ACCOUNT_MISMATCH", "配置 accountId 与 B站 UID 不一致");
            }
        } catch (NumberFormatException ignored) {
            // 非数字后缀由部署方自行保证唯一性。
        }
    }

    private static boolean isFatalAuth(String code) {
        return "BILI_AUTH_REQUIRED".equals(code)
                || "BILI_AUTH_INVALID".equals(code)
                || "BILI_ACCOUNT_MISMATCH".equals(code)
                || "BILI_CREDENTIAL_INVALID".equals(code);
    }

    private void sleepWithJitter() {
        long base = config.pollInterval().toMillis();
        long jitter = Math.max(1L, base / 10L);
        long delay = base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1L);
        sleepBackoff(Math.max(10_000L, delay));
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private void setStatus(AdapterStatus.ConnectionState state, String code,
                           String message) {
        status = new AdapterStatus(state, Instant.now(), code, message);
    }

    private void emit(AdapterEvent.Type type, String detail) {
        AdapterEvent event = new AdapterEvent(type, Instant.now(), detail);
        for (AdapterEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
                // 单个监听器不影响 Adapter。
            }
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
