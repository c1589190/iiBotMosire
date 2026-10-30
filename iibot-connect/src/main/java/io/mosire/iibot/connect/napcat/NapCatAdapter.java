package io.mosire.iibot.connect.napcat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.FriendRequestCapability;
import io.mosire.iibot.connect.core.FriendRequestEvent;
import io.mosire.iibot.connect.core.FriendRequestHandler;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.ForwardMessage;
import io.mosire.iibot.connect.core.ForwardPage;
import io.mosire.iibot.connect.core.GroupFileCapability;
import io.mosire.iibot.connect.core.GroupFileUploadCapability;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.QqGroupFile;
import io.mosire.iibot.connect.core.RecallCapability;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.RecentQqMessage;
import io.mosire.iibot.connect.core.RecentQqProvider;
import io.mosire.iibot.connect.core.AdapterEvent;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.AdapterStatus.ConnectionState;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.ops.OpsLog;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NapCat / OneBot 11 适配器。
 *
 * <p>传输策略：事件通过正向 WebSocket 根路径接收，动作通过 HTTP POST 一一对应。
 * 文本只以 OneBot text 段发送，避免 CQ Code 注入。发送回执严格区分
 * SENT、FAILED_BEFORE_SEND 与 UNKNOWN；超时或 IO 中断不会自动重试。</p>
 *
 * <p>线程安全：状态、发送幂等表、去重缓存与限速器均可并发访问；
 * {@link #close()} 幂等，并会完成所有未完成的发送 future。</p>
 */
public final class NapCatAdapter implements MessagingAdapter, AccountInfoProvider,
        ChatHistoryProvider, QqPlatformCapability, ForwardCapability, MediaCapability,
        GroupFileCapability, GroupFileUploadCapability, RecallCapability, AudioCapability,
        RecentQqProvider, FriendRequestCapability {

    private static final Logger LOG = LoggerFactory.getLogger(NapCatAdapter.class);
    private static final Pattern SAFE_EVENT_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int MAX_MEDIA_BYTES = 10 * 1024 * 1024;

    private final NapCatConfig config;
    private final String adapterId;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final RecentMessageDeduplicator deduplicator;
    private final TargetRateLimiter rateLimiter;

    private final AtomicReference<InboundHandler> inboundHandler = new AtomicReference<>();
    private final AtomicReference<FriendRequestHandler> friendRequestHandler =
            new AtomicReference<>();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicReference<AdapterStatus> status = new AtomicReference<>(AdapterStatus.stopped());
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final CopyOnWriteArrayList<AdapterEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<SendReceipt>> sendsByIdempotencyKey =
            new ConcurrentHashMap<>();
    private final Set<SendOperation> pendingSends = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> reconnectFuture;

    /**
     * 使用配置创建适配器。构造过程不发起任何网络连接。
     *
     * @param config 已完成校验的非 null 配置
     */
    public NapCatAdapter(NapCatConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.adapterId = config.accountId().map(id -> "napcat:" + id).orElse("napcat");
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iibot-connect-napcat");
            thread.setDaemon(true);
            return thread;
        });
        this.deduplicator = new RecentMessageDeduplicator(
                config.dedupeTtl(), config.maxDedupEntries());
        this.rateLimiter = new TargetRateLimiter(
                config.rateLimitPerSecond(), config.rateLimitBurst(), scheduler);
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public void start(InboundHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (closed.get()) {
            throw new IllegalStateException("适配器已关闭");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("适配器只能启动一次");
        }
        inboundHandler.set(handler);
        OpsLog.info("event=adapter_start adapterId={} config={}", adapterId, config);
        updateStatus(ConnectionState.CONNECTING, null, null);
        connect();
    }

    @Override
    public CompletionStage<SendReceipt> send(OutboundMessage message) {
        if (message == null) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("INVALID_MESSAGE", "发送消息不能为 null"));
        }
        boolean textOnly = message.richSegments().isEmpty();
        if (textOnly && (message.textSegments().isEmpty()
                || message.textSegments().stream().allMatch(String::isBlank))) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("EMPTY_TEXT", "空文本被拒绝"));
        }
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("ADAPTER_CLOSED", "适配器已关闭，未发送"));
        }
        return sendsByIdempotencyKey.computeIfAbsent(
                message.idempotencyKey(), ignored -> executeSend(message));
    }

    @Override
    public AdapterStatus status() {
        return status.get();
    }

    @Override
    public void addEventListener(AdapterEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeEventListener(AdapterEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * 执行 {@code get_login_info} 动作。该动作无外部副作用，但网络/协议错误仍会
     * 以异常形式完成返回的 future。
     */
    public CompletionStage<LoginInfo> getLoginInfo() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("适配器已关闭"));
        }
        return postAction("get_login_info", objectMapper.createObjectNode())
                .thenApply(root -> {
                    JsonNode data = root.path("data");
                    String userId = scalarText(data.get("user_id"));
                    String nickname = scalarText(data.get("nickname"));
                    if (userId == null) {
                        throw new CompletionException(
                                new OneBotActionException(
                                        FailureKind.INVALID_RESPONSE,
                                        "MISSING_LOGIN_INFO",
                                        "get_login_info 响应缺少 user_id"));
                    }
                    return new LoginInfo(userId, nickname == null ? "" : nickname);
                });
    }

    /**
     * 复用 {@link #getLoginInfo()} 查询账号信息，并映射为协议中立的 {@link AccountInfo}。
     *
     * <p>超时、HTTP 状态错误、retcode 错误与非法响应沿用动作调用的异常完成语义；不会自动重试，
     * 异常文本也不包含 token 或完整 URL。</p>
     */
    @Override
    public CompletionStage<AccountInfo> accountInfo() {
        return getLoginInfo().thenApply(info -> new AccountInfo(info.userId(), info.nickname()));
    }

    /**
     * 读取 NapCat 的文本聊天历史。
     *
     * <p>使用 NapCat 扩展动作 {@code get_friend_msg_history} / {@code get_group_msg_history}。
     * 只读、不重试；空历史或服务端 retcode 1200 映射为 {@link ChatHistoryPage#empty()}，
     * 其它错误保持异常完成。历史页中的图片、合并转发等非文本段跳过。</p>
     */
    @Override
    public CompletionStage<ChatHistoryPage> history(ChatHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        String action;
        if (query.sessionType() == InboundMessage.SessionType.PRIVATE) {
            action = "get_friend_msg_history";
            payload.put("user_id", query.targetId());
        } else {
            action = "get_group_msg_history";
            payload.put("group_id", query.targetId());
        }
        if (query.cursor() != null) {
            payload.put("message_seq", query.cursor());
        }
        payload.put("count", query.count());
        payload.put("reverse_order", query.reverseOrder());
        payload.put("parse_mult_msg", true);
        payload.put("disable_get_url", false);

        return postAction(action, payload).handle((root, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof OneBotActionException actionException
                        && "ONEBOT_RETCODE_1200".equals(actionException.errorCode)) {
                    return ChatHistoryPage.empty();
                }
                throw new CompletionException(cause);
            }
            JsonNode messagesNode = root.path("data").path("messages");
            if (!messagesNode.isArray()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_HISTORY_RESPONSE",
                        "历史响应缺少 messages 数组"));
            }
            List<ChatHistoryMessage> messages = new ArrayList<>();
            List<String> rawSequences = new ArrayList<>();
            int nonTextSkipped = 0;
            for (JsonNode messageNode : messagesNode) {
                String sequence = scalarText(messageNode.get("message_seq"));
                if (sequence != null) {
                    rawSequences.add(sequence);
                }
                ChatHistoryMessage message = parseHistoryMessage(messageNode);
                if (message != null) {
                    messages.add(message);
                } else {
                    nonTextSkipped++;
                }
            }
            boolean exhausted = messagesNode.size() < query.count();
            String nextCursor = null;
            if (!exhausted && !rawSequences.isEmpty()) {
                String first = rawSequences.getFirst();
                String last = rawSequences.getLast();
                nextCursor = query.reverseOrder()
                        ? previousSequence(first)
                        : nextSequence(last);
            }
            return new ChatHistoryPage(messages, nextCursor, exhausted, nonTextSkipped);
        });
    }

    @Override
    public void setFriendRequestHandler(FriendRequestHandler handler) {
        friendRequestHandler.set(handler);
    }

    @Override
    public CompletionStage<SendReceipt> decideFriendRequest(
            String flag, boolean approve, String remark) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (flag == null || flag.isBlank()) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("INVALID_FLAG", "好友申请 flag 为空"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("flag", flag.trim());
        payload.put("approve", approve);
        if (remark != null && !remark.isBlank()) {
            payload.put("remark", remark.trim());
        }
        return postAction("set_friend_add_request", payload)
                .thenApply(root -> SendReceipt.sent(List.of("friend-request")))
                .exceptionally(error -> SendReceipt.unknown(
                        "FRIEND_REQUEST_UNKNOWN",
                        "好友申请处理结果未知，禁止自动重试"));
    }

    @Override
    public CompletionStage<List<RecentQqMessage>> recentMessages(int limit) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (limit < 1 || limit > 50) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("limit 必须在 1..50"));
        }
        CompletionStage<List<ChatConversation>> groups = joinedGroups();
        CompletionStage<List<ChatConversation>> friends = friends(false);
        return groups.thenCombine(friends, (groupList, friendList) -> {
                    List<ChatConversation> all = new ArrayList<>(groupList);
                    all.addAll(friendList);
                    return all.stream().limit(50).toList();
                })
                .thenCompose(conversations -> {
                    List<CompletableFuture<RecentQqMessage>> futures = new ArrayList<>();
                    for (ChatConversation conversation : conversations) {
                        futures.add(history(ChatHistoryQuery.latest(
                                conversation.sessionType(), conversation.targetId(), 1))
                                .toCompletableFuture()
                                .thenApply(page -> page.messages().isEmpty() ? null
                                        : new RecentQqMessage(conversation.sessionType(),
                                                conversation.targetId(),
                                                conversation.displayName(),
                                                page.messages().getFirst()))
                                .exceptionally(ignored -> null));
                    }
                    return CompletableFuture.allOf(
                                    futures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> futures.stream()
                                    .map(CompletableFuture::join)
                                    .filter(Objects::nonNull)
                                    .sorted(java.util.Comparator.comparing(
                                            (RecentQqMessage message) -> message.lastMessage().sentAt(),
                                            java.util.Comparator.nullsLast(
                                                    java.util.Comparator.reverseOrder())))
                                    .limit(limit)
                                    .toList());
                });
    }

    @Override
    public CompletionStage<List<MessageSegment>> imageSegmentsForMessage(String messageId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (messageId == null || messageId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("messageId 不能为空"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message_id", messageId.trim());
        return postAction("get_msg", payload).thenApply(root -> {
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_MESSAGE_RESPONSE",
                        "消息响应缺少 data 对象"));
            }
            return OneBot11Codec.extractSegments(data.get("message")).stream()
                    .filter(segment -> segment.type() == MessageSegment.Type.IMAGE)
                    .toList();
        });
    }

    @Override
    public CompletionStage<MediaAsset> image(MessageSegment segment) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (segment == null || segment.type() != MessageSegment.Type.IMAGE) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("segment 必须是 image 段"));
        }
        if (!segment.url().isBlank()) {
            return downloadImage(segment.url(), segment.fileId());
        }
        if (segment.fileId().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("图片缺少 file/url"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("file", segment.fileId());
        return postAction("get_image", payload).thenCompose(root -> {
            String url = scalarText(root.path("data").get("url"));
            if (url == null || url.isBlank()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "IMAGE_URL_MISSING",
                        "get_image 未返回 url"));
            }
            return downloadImage(url, segment.fileId());
        });
    }

    @Override
    public CompletionStage<SendReceipt> uploadGroupFile(String groupId, String fileName,
                                                        String mediaType, byte[] bytes,
                                                        String idempotencyKey) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (groupId == null || groupId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("groupId 不能为空"));
        }
        if (fileName == null || fileName.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("fileName 不能为空"));
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_MEDIA_BYTES) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("群文件字节为空或超过 10 MiB"));
        }
        final Path temporary;
        try {
            String suffix = fileSuffix(fileName);
            temporary = Files.createTempFile("iibot-upload-", suffix);
            Files.write(temporary, bytes);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("创建群文件临时文件失败", failure));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("group_id", groupId.trim());
        payload.put("file", temporary.toAbsolutePath().toString());
        payload.put("name", fileName.trim());
        payload.put("folder", "/");
        CompletionStage<SendReceipt> stage;
        try {
            stage = postAction("upload_group_file", payload).thenApply(root -> {
                JsonNode data = root.path("data");
                String receiptId = scalarText(data.path("message_id").isMissingNode()
                        ? data.get("file_id") : data.get("message_id"));
                return SendReceipt.sent(List.of(receiptId == null
                        ? "group-file:" + fileName.trim() : receiptId));
            });
        } catch (RuntimeException failure) {
            deleteQuietly(temporary);
            return CompletableFuture.failedFuture(failure);
        }
        return stage.whenComplete((ignored, failure) -> deleteQuietly(temporary));
    }

    private static String fileSuffix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1 || fileName.length() - dot > 16) {
            return ".bin";
        }
        String suffix = fileName.substring(dot);
        return suffix.matches("\\.[A-Za-z0-9]{1,15}") ? suffix : ".bin";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // best effort; path is local temp and never logged
        }
    }

    @Override
    public CompletionStage<MediaAsset> audio(MessageSegment segment) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (segment == null || segment.type() != MessageSegment.Type.AUDIO) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("segment 必须是 audio 段"));
        }
        if (!segment.url().isBlank()) {
            return downloadAudio(segment.url(), segment.fileId());
        }
        if (segment.fileId().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("语音缺少 file/url"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("file", segment.fileId());
        return postAction("get_record", payload).thenCompose(root -> {
            JsonNode data = root.path("data");
            String url = data.isTextual() ? data.asText()
                    : firstScalar(data, "url", "file", "path");
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "AUDIO_URL_MISSING",
                        "get_record 未返回可下载 URL"));
            }
            return downloadAudio(url, segment.fileId());
        });
    }

    private CompletionStage<MediaAsset> downloadAudio(String rawUrl, String fileName) {
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("音频 URL 非法"));
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("音频 URL scheme 不允许"));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(config.requestTimeout())
                    .build();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("音频 URL 非法"));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.HTTP_STATUS, "AUDIO_HTTP_" + status,
                                "语音下载失败"));
                    }
                    byte[] bytes = response.body();
                    if (bytes.length == 0 || bytes.length > MAX_MEDIA_BYTES) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.INVALID_RESPONSE, "AUDIO_SIZE_INVALID",
                                "语音大小超出限制"));
                    }
                    String type = response.headers().firstValue("content-type")
                            .orElseGet(() -> mediaTypeFromFileName(fileName));
                    int semi = type.indexOf(';');
                    if (semi >= 0) {
                        type = type.substring(0, semi);
                    }
                    return new MediaAsset(
                            fileName == null || fileName.isBlank() ? "voice" : fileName,
                            type, bytes);
                });
    }

    @Override
    public CompletionStage<SendReceipt> recall(String messageId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (messageId == null || messageId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("messageId 不能为空"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message_id", messageId.trim());
        return postAction("delete_msg", payload)
                .thenApply(root -> SendReceipt.sent(List.of(messageId.trim())));
    }

    @Override
    public CompletionStage<List<QqGroupFile>> groupFiles(String groupId, String folderId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (groupId == null || groupId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("groupId 不能为空"));
        }
        String normalizedFolder = folderId == null || folderId.isBlank()
                ? "/" : folderId.trim();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("group_id", groupId.trim());
        payload.put("folder_id", normalizedFolder);
        return postAction("get_group_files_by_folder", payload).thenApply(root -> {
            JsonNode data = root.path("data");
            JsonNode files = data.isArray() ? data : data.path("files");
            if (!files.isArray()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_GROUP_FILE_RESPONSE",
                        "群文件目录响应缺少 data/files 数组"));
            }
            List<QqGroupFile> result = new ArrayList<>();
            for (JsonNode item : files) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String fileId = firstScalar(item, "file_id", "fileId", "id");
                if (fileId == null) {
                    continue;
                }
                String name = firstScalar(item, "file_name", "fileName", "name");
                long size = firstLong(item, "file_size", "fileSize", "size");
                JsonNode uploader = item.path("uploader");
                String uploaderId = firstScalar(uploader, "user_id", "userId", "id");
                if (uploaderId == null) {
                    uploaderId = firstScalar(item, "uploader_id", "uploaderId");
                }
                String uploaderName = firstScalar(uploader,
                        "nickname", "card", "name");
                if (uploaderName == null) {
                    uploaderName = firstScalar(item, "uploader_name", "uploaderName");
                }
                Instant uploadedAt = parseTimestamp(firstScalar(item,
                        "upload_time", "uploadTime", "modify_time", "create_time"));
                String busid = firstScalar(item, "busid", "bus_id", "busId");
                result.add(new QqGroupFile(fileId, name, size, uploaderId,
                        uploaderName, uploadedAt, busid));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletionStage<MediaAsset> downloadGroupFile(String groupId, QqGroupFile file) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (groupId == null || groupId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("groupId 不能为空"));
        }
        if (file == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("file 不能为空"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("group_id", groupId.trim());
        payload.put("file_id", file.fileId());
        payload.put("busid", parseBusid(file.busid()));
        return postAction("get_group_file_url", payload).thenCompose(root -> {
            JsonNode data = root.path("data");
            String url = data.isTextual() ? data.asText()
                    : firstScalar(data, "url", "file_url", "download_url");
            if (url == null || url.isBlank()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "GROUP_FILE_URL_MISSING",
                        "get_group_file_url 未返回下载地址"));
            }
            return downloadGroupFileBytes(url, file);
        });
    }

    private CompletionStage<MediaAsset> downloadGroupFileBytes(String rawUrl,
                                                               QqGroupFile file) {
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("群文件 URL 非法"));
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("群文件 URL scheme 不允许"));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(config.requestTimeout())
                    .build();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("群文件 URL 非法"));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.HTTP_STATUS, "GROUP_FILE_HTTP_" + status,
                                "群文件下载失败"));
                    }
                    byte[] bytes = response.body();
                    if (bytes.length == 0 || bytes.length > MAX_MEDIA_BYTES) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.INVALID_RESPONSE, "GROUP_FILE_SIZE_INVALID",
                                "群文件大小超出限制"));
                    }
                    String type = response.headers().firstValue("content-type")
                            .orElseGet(() -> mediaTypeFromFileName(file.name()));
                    int semi = type.indexOf(';');
                    if (semi >= 0) {
                        type = type.substring(0, semi);
                    }
                    return new MediaAsset(file.name(), type, bytes);
                });
    }

    private static int parseBusid(String value) {
        try {
            int busid = Integer.parseInt(value);
            return busid > 0 ? busid : 102;
        } catch (RuntimeException ignored) {
            return 102;
        }
    }

    private static String mediaTypeFromFileName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")
                || lower.endsWith(".csv")) {
            return "text/plain";
        }
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".amr")) return "audio/amr";
        if (lower.endsWith(".silk")) return "audio/silk";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private CompletionStage<MediaAsset> downloadImage(String rawUrl, String fileName) {
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("图片 URL 非法"));
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("图片 URL scheme 不允许"));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(config.requestTimeout())
                    .build();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("图片 URL 非法"));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.HTTP_STATUS, "IMAGE_HTTP_" + status,
                                "图片下载失败"));
                    }
                    byte[] bytes = response.body();
                    if (bytes.length == 0 || bytes.length > MAX_MEDIA_BYTES) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.INVALID_RESPONSE, "IMAGE_SIZE_INVALID",
                                "图片大小超出限制"));
                    }
                    String type = response.headers().firstValue("content-type")
                            .orElseGet(() -> mediaTypeFromName(fileName));
                    int semi = type.indexOf(';');
                    if (semi >= 0) {
                        type = type.substring(0, semi);
                    }
                    return new MediaAsset(
                            fileName == null || fileName.isBlank() ? "image" : fileName,
                            type, bytes);
                });
    }

    private static String mediaTypeFromName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    @Override
    public CompletionStage<ForwardPage> forward(String forwardId, int limit) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (forwardId == null || forwardId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("forwardId 不能为空"));
        }
        if (limit < 1 || limit > 200) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("limit 必须在 1..200"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", forwardId.trim());
        return postAction("get_forward_msg", payload).thenApply(root -> {
            JsonNode messagesNode = root.path("data").path("messages");
            if (!messagesNode.isArray()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_FORWARD_RESPONSE",
                        "转发响应缺少 messages 数组"));
            }
            List<ForwardMessage> messages = new ArrayList<>();
            boolean truncated = false;
            for (JsonNode node : messagesNode) {
                if (messages.size() >= limit) {
                    truncated = true;
                    break;
                }
                JsonNode content = node.get("content");
                String text = OneBot11Codec.extractText(content);
                List<io.mosire.iibot.connect.core.MessageSegment> segments =
                        OneBot11Codec.extractSegments(content);
                if (text.isBlank() && segments.isEmpty()) {
                    continue;
                }
                JsonNode sender = node.path("sender");
                String senderId = scalarText(sender.get("user_id"));
                String senderName = scalarText(sender.get("nickname"));
                if (senderId == null) {
                    senderId = "";
                }
                messages.add(new ForwardMessage(senderId,
                        senderName == null ? "" : senderName,
                        parseEpoch(scalarText(node.get("time"))), text, segments));
            }
            return new ForwardPage(messages, truncated);
        });
    }

    @Override
    public CompletionStage<List<ChatConversation>> recentConversations(int count) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (count < 1 || count > 200) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "count 必须在 1–200 之间"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("count", count);
        return postAction("get_recent_contact", payload)
                .handle((root, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(parseRecent(root, count));
                    }
                    Throwable cause = unwrap(error);
                    if (isMissingRecentContactAction(cause)) {
                        // LLBot 等轻量 OneBot 实现没有 get_recent_contact；
                        // 用已加入群列表作为可用目录兜底，不伪造“最近”时间。
                        return joinedGroups().thenApply(groups -> limitConversations(groups, count));
                    }
                    throw new CompletionException(cause);
                })
                .thenCompose(stage -> stage);
    }

    private List<ChatConversation> parseRecent(JsonNode root, int count) {
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new CompletionException(new OneBotActionException(
                    FailureKind.INVALID_RESPONSE, "INVALID_DIRECTORY_RESPONSE",
                    "最近会话响应缺少 data 数组"));
        }
        List<ChatConversation> result = new ArrayList<>();
        for (JsonNode item : data) {
            ChatConversation conversation = parseRecentConversation(item);
            if (conversation != null && result.size() < count) {
                result.add(conversation);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isMissingRecentContactAction(Throwable cause) {
        if (!(cause instanceof OneBotActionException actionException)) {
            return false;
        }
        return "HTTP_404".equals(actionException.errorCode)
                || "ONEBOT_RETCODE_404".equals(actionException.errorCode);
    }

    private static List<ChatConversation> limitConversations(
            List<ChatConversation> conversations, int count) {
        if (conversations.size() <= count) {
            return List.copyOf(conversations);
        }
        return List.copyOf(conversations.subList(0, count));
    }

    @Override
    public CompletionStage<List<ChatConversation>> joinedGroups() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        return postAction("get_group_list", objectMapper.createObjectNode()).thenApply(root -> {
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_DIRECTORY_RESPONSE",
                        "群列表响应缺少 data 数组"));
            }
            List<ChatConversation> result = new ArrayList<>();
            for (JsonNode item : data) {
                String groupId = scalarText(item.get("group_id"));
                if (groupId == null || groupId.isBlank()) {
                    continue;
                }
                String groupName = scalarText(item.get("group_name"));
                String remark = scalarText(item.get("group_remark"));
                result.add(new ChatConversation(InboundMessage.SessionType.GROUP,
                        groupId, groupName == null ? "" : groupName,
                        remark == null ? "" : remark, null, null));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletionStage<List<ChatConversation>> friends(boolean noCache) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("no_cache", noCache);
        return postAction("get_friend_list", payload).thenApply(root -> {
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_DIRECTORY_RESPONSE",
                        "好友列表响应缺少 data 数组"));
            }
            List<ChatConversation> result = new ArrayList<>();
            for (JsonNode item : data) {
                String userId = scalarText(item.get("user_id"));
                if (userId == null || userId.isBlank()) {
                    continue;
                }
                String nickname = scalarText(item.get("nickname"));
                String remark = scalarText(item.get("remark"));
                result.add(new ChatConversation(InboundMessage.SessionType.PRIVATE,
                        userId, nickname == null ? "" : nickname,
                        remark == null ? "" : remark, null, null));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletionStage<List<GroupMember>> groupMembers(String groupId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (groupId == null || groupId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("groupId 不能为空"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("group_id", groupId.trim());
        return postAction("get_group_member_list", payload).thenApply(root -> {
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_DIRECTORY_RESPONSE",
                        "群成员响应缺少 data 数组"));
            }
            List<GroupMember> result = new ArrayList<>();
            for (JsonNode item : data) {
                String qqId = scalarText(item.get("user_id"));
                if (qqId == null || qqId.isBlank()) {
                    continue;
                }
                String nickname = scalarText(item.get("nickname"));
                String card = scalarText(item.get("card"));
                String role = scalarText(item.get("role"));
                result.add(new GroupMember(groupId.trim(), qqId,
                        nickname == null ? "" : nickname,
                        card == null ? "" : card,
                        role == null ? "" : role));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletionStage<ChatMessageDetail> message(String messageId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        if (messageId == null || messageId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("messageId 不能为空"));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message_id", messageId.trim());
        return postAction("get_msg", payload).thenApply(root -> {
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_MESSAGE_RESPONSE",
                        "消息响应缺少 data 对象"));
            }
            String resolvedId = scalarText(data.get("message_id"));
            if (resolvedId == null) {
                resolvedId = messageId.trim();
            }
            String groupId = scalarText(data.get("group_id"));
            String senderId = scalarText(data.path("sender").get("user_id"));
            if (senderId == null) {
                senderId = scalarText(data.get("user_id"));
            }
            if (senderId == null) {
                throw new CompletionException(new OneBotActionException(
                        FailureKind.INVALID_RESPONSE, "INVALID_MESSAGE_RESPONSE",
                        "消息响应缺少发送者"));
            }
            boolean group = groupId != null && !groupId.isBlank();
            String targetId = group ? groupId : senderId;
            String senderName = scalarText(data.path("sender").get("card"));
            if (senderName == null) {
                senderName = scalarText(data.path("sender").get("nickname"));
            }
            if (senderName == null) {
                senderName = senderId;
            }
            String text = OneBot11Codec.extractText(data.get("message"));
            Instant sentAt = parseEpoch(scalarText(data.get("time")));
            return new ChatMessageDetail(resolvedId,
                    group ? InboundMessage.SessionType.GROUP : InboundMessage.SessionType.PRIVATE,
                    targetId, senderId, senderName, text == null ? "" : text, sentAt,
                    countNonTextSegments(data.get("message")));
        });
    }

    @Override
    public CompletionStage<SendReceipt> sendPrivate(String targetQqId, String temporaryGroupId,
                                                    List<String> textSegments,
                                                    String idempotencyKey) {
        if (targetQqId == null || targetQqId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("targetQqId 不能为空"));
        }
        OutboundMessage message = temporaryGroupId == null || temporaryGroupId.isBlank()
                ? OutboundMessage.privateMessage(targetQqId.trim(), textSegments, idempotencyKey)
                : OutboundMessage.privateTemporaryMessage(targetQqId.trim(),
                        temporaryGroupId.trim(), textSegments, idempotencyKey);
        return send(message);
    }

    @Override
    public CompletionStage<SendReceipt> sendGroup(String groupId, List<String> textSegments,
                                                  String idempotencyKey) {
        if (groupId == null || groupId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("groupId 不能为空"));
        }
        return send(OutboundMessage.groupMessage(groupId.trim(), textSegments, idempotencyKey));
    }

    private static ChatConversation parseRecentConversation(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String targetId = scalarText(item.get("peerUin"));
        if (targetId == null || targetId.isBlank()) {
            return null;
        }
        JsonNode latest = item.path("lastestMsg");
        String messageType = scalarText(latest.get("message_type"));
        InboundMessage.SessionType type;
        if ("group".equalsIgnoreCase(messageType)) {
            type = InboundMessage.SessionType.GROUP;
        } else if ("private".equalsIgnoreCase(messageType)) {
            type = InboundMessage.SessionType.PRIVATE;
        } else {
            String chatType = scalarText(item.get("chatType"));
            type = "2".equals(chatType)
                    ? InboundMessage.SessionType.GROUP
                    : InboundMessage.SessionType.PRIVATE;
        }
        if (type == InboundMessage.SessionType.GROUP) {
            String groupId = scalarText(latest.get("group_id"));
            if (groupId != null && !groupId.isBlank()) {
                targetId = groupId;
            }
        }
        String displayName = scalarText(item.get("peerName"));
        String remark = scalarText(item.get("remark"));
        Instant lastAt = parseEpoch(scalarText(item.get("msgTime")));
        if (lastAt == null) {
            lastAt = parseEpoch(scalarText(latest.get("time")));
        }
        String lastId = scalarText(item.get("msgId"));
        return new ChatConversation(type, targetId,
                displayName == null ? "" : displayName,
                remark == null ? "" : remark, lastAt, lastId);
    }

    private static Instant parseEpoch(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long epoch = Long.parseLong(raw.trim());
            return epoch > 0 ? Instant.ofEpochSecond(epoch) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Instant parseTimestamp(String raw) {
        Instant epoch = parseEpoch(raw);
        if (epoch != null || raw == null || raw.isBlank()) {
            return epoch;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstScalar(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            String value = scalarText(node.get(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static long firstLong(JsonNode node, String... fields) {
        String value = firstScalar(node, fields);
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static int countNonTextSegments(JsonNode message) {
        if (message == null || !message.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode segment : message) {
            String type = scalarText(segment.get("type"));
            if (type != null && !"text".equalsIgnoreCase(type)) {
                count++;
            }
        }
        return count;
    }

    private static String previousSequence(String value) {
        try {
            long sequence = Long.parseLong(value);
            return sequence <= 1L ? null : Long.toString(sequence - 1L);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static String nextSequence(String value) {
        try {
            return Long.toString(Math.addExact(Long.parseLong(value), 1L));
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static ChatHistoryMessage parseHistoryMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String messageId = scalarText(node.get("message_id"));
        JsonNode sender = node.path("sender");
        String senderId = scalarText(sender.get("user_id"));
        if (senderId == null) {
            senderId = scalarText(node.get("user_id"));
        }
        if (messageId == null || senderId == null) {
            return null;
        }
        String senderName = scalarText(sender.get("card"));
        if (senderName == null) {
            senderName = scalarText(sender.get("nickname"));
        }
        if (senderName == null) {
            senderName = senderId;
        }
        String text = OneBot11Codec.extractText(node.get("message"));
        List<io.mosire.iibot.connect.core.MessageSegment> segments =
                OneBot11Codec.extractSegments(node.get("message"));
        if (text.isBlank() && segments.isEmpty()) {
            return null;
        }
        Instant sentAt = null;
        JsonNode timeNode = node.get("time");
        if (timeNode != null && timeNode.isIntegralNumber()) {
            long epochSeconds = timeNode.asLong();
            if (epochSeconds > 0) {
                try {
                    sentAt = Instant.ofEpochSecond(epochSeconds);
                } catch (RuntimeException ignored) {
                    sentAt = null;
                }
            }
        }
        return new ChatHistoryMessage(messageId, senderId, senderName, text, sentAt, segments);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> pendingReconnect = reconnectFuture;
        if (pendingReconnect != null) {
            pendingReconnect.cancel(false);
        }
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            try {
                socket.abort();
            } catch (RuntimeException ignored) {
                // abort 是尽力关闭；后续状态仍必须收敛到 CLOSED。
            }
        }
        failPendingSends();
        inboundHandler.set(null);
        friendRequestHandler.set(null);
        updateStatus(ConnectionState.CLOSED, null, null);
        emit(AdapterEvent.Type.DISCONNECTED, "adapter closed");
        scheduler.shutdownNow();
        OpsLog.info("event=adapter_close adapterId={}", adapterId);
    }

    private CompletableFuture<SendReceipt> executeSend(OutboundMessage message) {
        List<List<OutboundSegment>> chunks;
        try {
            chunks = message.richSegments().isEmpty()
                    ? TextSplitter.split(message.textSegments(), config.maxTextLength()).stream()
                            .map(text -> List.<OutboundSegment>of(new OutboundSegment.Text(text)))
                            .toList()
                    : RichMessageSplitter.split(message.richSegments(), config.maxTextLength());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(SendReceipt.failed(
                    "INVALID_TEXT",
                    Redaction.sanitize(exception.getMessage(), config.accessToken().orElse(null))));
        }
        SendOperation operation = new SendOperation(message, chunks);
        OpsLog.info("event=adapter_send_start key={} targetType={} targetId={} chunks={}",
                message.idempotencyKey(), message.targetType(), message.targetId(),
                chunks.size());
        pendingSends.add(operation);
        operation.result.whenComplete((ignored, error) -> pendingSends.remove(operation));
        sendChunk(operation, 0);
        return operation.result;
    }

    private void sendChunk(SendOperation operation, int index) {
        if (operation.finished.get()) {
            return;
        }
        if (closed.get()) {
            finish(operation, SendReceipt.failed("ADAPTER_CLOSED", "适配器已关闭，未发送"));
            return;
        }
        if (index >= operation.chunks.size()) {
            finish(operation, SendReceipt.sent(operation.messageIds));
            return;
        }

        CompletableFuture<Void> token;
        try {
            token = rateLimiter.acquire(targetKey(operation.message));
        } catch (RuntimeException exception) {
            finish(operation, SendReceipt.unknown(
                    "RATE_LIMITER_FAILED", "限速器不可用，发送状态未知"));
            return;
        }
        token.whenComplete((ignored, rateError) -> {
            if (operation.finished.get()) {
                return;
            }
            if (rateError != null) {
                finish(operation, SendReceipt.unknown(
                        "RATE_LIMITER_FAILED", "限速器不可用，发送状态未知"));
                return;
            }
            if (closed.get()) {
                finish(operation, SendReceipt.failed("ADAPTER_CLOSED", "适配器已关闭，未发送"));
                return;
            }
            operation.requestEverStarted.set(true);
            PreparedSendAction prepared = prepareSendAction(
                    operation.message, operation.chunks.get(index));
            postAction(prepared.action, prepared.payload)
                    .whenComplete((response, sendError) -> {
                        if (operation.finished.get()) {
                            return;
                        }
                        if (sendError != null) {
                            finish(operation, failureReceipt(operation, unwrap(sendError)));
                            return;
                        }
                        String messageId = extractMessageId(response);
                        if (messageId == null) {
                            finish(operation, SendReceipt.unknown(
                                    "MISSING_MESSAGE_ID",
                                    "HTTP 响应缺少 message_id，消息可能已发送",
                                    List.copyOf(operation.messageIds)));
                            return;
                        }
                        operation.messageIds.add(messageId);
                        sendChunk(operation, index + 1);
                    });
        });
    }

    private static boolean isLlbBotProactiveTempFailure(OneBotActionException failure) {
        if (!"ONEBOT_RETCODE_200".equals(failure.errorCode)
                && !"ONEBOT_RETCODE_1200".equals(failure.errorCode)) {
            return false;
        }
        String message = failure.getMessage();
        return message != null && message.contains("先添加对方为好友");
    }

    private SendReceipt failureReceipt(SendOperation operation, Throwable throwable) {
        if (throwable instanceof OneBotActionException actionException) {
            if (actionException.kind == FailureKind.REJECTED) {
                if (operation.messageIds.isEmpty()) {
                    if (operation.message.temporaryGroupId() != null
                            && isLlbBotProactiveTempFailure(actionException)) {
                        return SendReceipt.failed(
                                "LLBOT_TEMP_CHAT_REQUIRES_INBOUND",
                                "LLBot 不支持主动群临时会话；请让目标先从群里私聊 Bot 一次，"
                                        + "再显式重试送达");
                    }
                    return SendReceipt.failed(
                            actionException.errorCode,
                            actionException.getMessage());
                }
                return SendReceipt.unknown(
                        "PARTIAL_" + actionException.errorCode,
                        "部分文本段已发送，后续文本段被服务端拒绝",
                        List.copyOf(operation.messageIds));
            }
            return SendReceipt.unknown(
                    actionException.errorCode,
                    actionException.getMessage(),
                    List.copyOf(operation.messageIds));
        }
        return SendReceipt.unknown(
                "SEND_IO_ERROR",
                Redaction.errorSummary(throwable, config.accessToken().orElse(null)),
                List.copyOf(operation.messageIds));
    }

    private void finish(SendOperation operation, SendReceipt receipt) {
        if (operation.finished.compareAndSet(false, true)) {
            OpsLog.info("event=adapter_send_finish key={} state={} code={} messageIds={}",
                    operation.message.idempotencyKey(), receipt.state(),
                    receipt.errorCode() == null ? "" : receipt.errorCode(),
                    receipt.adapterMessageIds());
            operation.result.complete(receipt);
        }
    }

    private void failPendingSends() {
        for (SendOperation operation : pendingSends) {
            if (operation.requestEverStarted.get()) {
                finish(operation, SendReceipt.unknown(
                        "ADAPTER_CLOSED_DURING_SEND",
                        "关闭时发送请求已开始，消息可能已发出",
                        List.copyOf(operation.messageIds)));
            } else {
                finish(operation, SendReceipt.failed(
                        "ADAPTER_CLOSED", "关闭时发送请求尚未开始"));
            }
        }
    }

    private PreparedSendAction prepareSendAction(
            OutboundMessage message, List<OutboundSegment> segments) {
        boolean privateTarget = message.targetType() == OutboundMessage.TargetType.PRIVATE;
        boolean temporaryPrivate = privateTarget && message.temporaryGroupId() != null;
        String action;
        ObjectNode payload = objectMapper.createObjectNode();
        if (config.sendActionMode() == NapCatConfig.SendActionMode.DEDICATED
                || temporaryPrivate) {
            action = privateTarget ? "send_private_msg" : "send_group_msg";
            payload.put(privateTarget ? "user_id" : "group_id", message.targetId());
        } else {
            action = "send_msg";
            payload.put("message_type", privateTarget ? "private" : "group");
            payload.put(privateTarget ? "user_id" : "group_id", message.targetId());
        }
        if (temporaryPrivate) {
            payload.put("group_id", message.temporaryGroupId());
        }
        ArrayNode content = objectMapper.createArrayNode();
        for (OutboundSegment source : segments) {
            ObjectNode segment = objectMapper.createObjectNode();
            if (source instanceof OutboundSegment.Text text) {
                segment.put("type", "text");
                segment.putObject("data").put("text", text.text());
            } else if (source instanceof OutboundSegment.Mention mention) {
                segment.put("type", "at");
                segment.putObject("data").put("qq", mention.qqId());
            } else if (source instanceof OutboundSegment.MentionAll) {
                segment.put("type", "at");
                segment.putObject("data").put("qq", "all");
            } else if (source instanceof OutboundSegment.Image image) {
                if (image.bytes().length == 0) {
                    throw new IllegalStateException("出站图片字节未解析");
                }
                segment.put("type", "image");
                segment.putObject("data").put("file", "base64://"
                        + Base64.getEncoder().encodeToString(image.bytes()));
            } else if (source instanceof OutboundSegment.Sticker sticker) {
                segment.put("type", "face");
                segment.putObject("data").put("id", sticker.faceId());
            } else if (source instanceof OutboundSegment.Reply reply) {
                segment.put("type", "reply");
                segment.putObject("data").put("id", reply.messageId());
            } else {
                throw new IllegalStateException("不支持的出站消息段: " + source.getClass());
            }
            content.add(segment);
        }
        payload.set("message", content);
        return new PreparedSendAction(action, payload);
    }

    private CompletableFuture<JsonNode> postAction(String action, JsonNode payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT, "ADAPTER_CLOSED", "适配器已关闭"));
        }
        final HttpRequest request;
        try {
            URI endpoint = actionUri(action);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(config.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            config.accessToken().ifPresent(token ->
                    builder.header("Authorization", "Bearer " + token));
            request = builder.build();
        } catch (JsonProcessingException | RuntimeException exception) {
            return CompletableFuture.failedFuture(new OneBotActionException(
                    FailureKind.TRANSPORT,
                    "REQUEST_BUILD_FAILED",
                    Redaction.errorSummary(exception, config.accessToken().orElse(null))));
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.TRANSPORT,
                                "HTTP_IO_ERROR",
                                Redaction.errorSummary(cause, config.accessToken().orElse(null))));
                    }
                    if (response.statusCode() != 200) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.HTTP_STATUS,
                                "HTTP_" + response.statusCode(),
                                "HTTP 动作返回非 200 状态"));
                    }
                    JsonNode body;
                    try {
                        body = objectMapper.readTree(response.body());
                    } catch (JsonProcessingException exception) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.INVALID_RESPONSE,
                                "INVALID_JSON_RESPONSE",
                                "HTTP 动作响应不是合法 JSON"));
                    }
                    if (body == null || !body.isObject()) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.INVALID_RESPONSE,
                                "INVALID_RESPONSE",
                                "HTTP 动作响应不是 JSON 对象"));
                    }
                    JsonNode retcodeNode = body.get("retcode");
                    if (retcodeNode == null || !retcodeNode.isIntegralNumber()) {
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.INVALID_RESPONSE,
                                "MISSING_RETCODE",
                                "HTTP 动作响应缺少 retcode"));
                    }
                    long retcode = retcodeNode.asLong();
                    if (retcode != 0L) {
                        String serverMessage = scalarText(body.get("message"));
                        if (serverMessage == null || serverMessage.isBlank()) {
                            serverMessage = scalarText(body.get("wording"));
                        }
                        String safeMessage = Redaction.sanitize(
                                serverMessage, config.accessToken().orElse(null));
                        if (safeMessage.isBlank()) {
                            safeMessage = "OneBot 动作被服务端拒绝";
                        }
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.REJECTED,
                                "ONEBOT_RETCODE_" + retcode,
                                safeMessage));
                    }
                    return body;
                });
    }

    private URI actionUri(String action) {
        URI base = config.httpBaseUri();
        String path = base.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        } else if (!path.endsWith("/")) {
            path = path + "/";
        }
        String authority = base.getRawAuthority();
        return URI.create(base.getScheme() + "://" + authority + path + action);
    }

    private String extractMessageId(JsonNode response) {
        JsonNode data = response.path("data");
        return scalarText(data.get("message_id"));
    }

    private String targetKey(OutboundMessage message) {
        return message.targetType() + ":" + message.targetId();
    }

    private void connect() {
        if (closed.get()) {
            return;
        }
        final URI eventUri;
        try {
            eventUri = buildEventWebSocketUri();
        } catch (RuntimeException exception) {
            recordConnectionFailure("WS_URI_INVALID", exception);
            scheduleReconnect();
            return;
        }

        WebSocket.Builder builder;
        try {
            builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(config.connectTimeout());
            String token = config.accessToken().orElse(null);
            if (token != null && config.wsAuthMode() != NapCatConfig.WsAuthMode.QUERY) {
                builder.header("Authorization", "Bearer " + token);
            }
            builder.buildAsync(eventUri, new EventListener()).whenComplete((socket, error) -> {
                if (error != null) {
                    if (!closed.get()) {
                        recordConnectionFailure("WS_CONNECT_FAILED", unwrap(error));
                        scheduleReconnect();
                    }
                } else if (closed.get()) {
                    socket.abort();
                }
            });
        } catch (RuntimeException exception) {
            recordConnectionFailure("WS_CONNECT_FAILED", exception);
            scheduleReconnect();
        }
    }

    private URI buildEventWebSocketUri() {
        URI base = config.eventWebSocketUri();
        String token = config.accessToken().orElse(null);
        if (token == null || config.wsAuthMode() == NapCatConfig.WsAuthMode.BEARER) {
            return base;
        }
        String raw = base.toString();
        String query = base.getRawQuery();
        if (query != null && (query.equals("access_token") || query.contains("access_token="))) {
            return base;
        }
        String separator = raw.contains("?") ? "&" : "?";
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        return URI.create(raw + separator + "access_token=" + encoded);
    }

    private void scheduleReconnect() {
        if (closed.get() || !started.get()) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delayMillis = reconnectDelayMillis(attempt);
        updateStatus(
                ConnectionState.RECONNECTING,
                "RECONNECT_SCHEDULED",
                "第 " + attempt + " 次重连将在 " + delayMillis + "ms 后开始");
        emit(AdapterEvent.Type.RECONNECT_SCHEDULED,
                "attempt=" + attempt + ",delayMs=" + delayMillis);
        try {
            reconnectFuture = scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                if (!closed.get()) {
                    updateStatus(ConnectionState.RECONNECTING, null, null);
                    connect();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException exception) {
            reconnectScheduled.set(false);
            recordConnectionFailure("RECONNECT_SCHEDULE_FAILED", exception);
        }
    }

    private long reconnectDelayMillis(int attempt) {
        long initial = Math.max(1L, config.initialReconnectDelay().toMillis());
        long max = Math.max(initial, config.maxReconnectDelay().toMillis());
        double multiplier = Math.pow(2.0, Math.max(0, attempt - 1));
        long base = (long) Math.min((double) max, initial * multiplier);
        if (config.reconnectJitter() == 0.0) {
            return base;
        }
        long jitterRange = (long) (base * config.reconnectJitter());
        if (jitterRange <= 0) {
            return base;
        }
        long jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        return Math.max(0L, Math.min(max, base + jitter));
    }

    private void recordConnectionFailure(String code, Throwable throwable) {
        String summary = Redaction.errorSummary(throwable, config.accessToken().orElse(null));
        updateStatus(ConnectionState.RECONNECTING, code, summary);
        emit(AdapterEvent.Type.ERROR, code + ": " + summary);
    }

    private void handleFrame(String payload) {
        if (payload == null || payload.isBlank() || closed.get()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || root.isNull()) {
                return;
            }
            if (root.isArray()) {
                for (JsonNode item : root) {
                    handleEvent(item);
                }
            } else {
                handleEvent(root);
            }
        } catch (JsonProcessingException exception) {
            emit(AdapterEvent.Type.ERROR, "INVALID_EVENT_JSON");
        } catch (RuntimeException exception) {
            emit(AdapterEvent.Type.ERROR, "EVENT_HANDLING_FAILED");
        }
    }

    private void handleEvent(JsonNode event) {
        if (event == null || !event.isObject() || closed.get()) {
            return;
        }
        String postType = safeEventName(event.path("post_type").asText(""));
        if ("message".equals(postType)) {
            handleMessageEvent(event);
            return;
        }
        if ("request".equals(postType)) {
            Optional<FriendRequestEvent> request = new OneBot11Codec()
                    .decodeFriendRequest(event, Instant.now());
            if (request.isPresent()) {
                FriendRequestHandler handler = friendRequestHandler.get();
                if (handler != null) {
                    try {
                        handler.onFriendRequest(request.orElseThrow());
                    } catch (RuntimeException exception) {
                        emit(AdapterEvent.Type.ERROR, "FRIEND_REQUEST_HANDLER_FAILED");
                    }
                }
            } else {
                emit(AdapterEvent.Type.IGNORED_EVENT, "request_type="
                        + safeEventName(event.path("request_type").asText("")));
            }
            return;
        }
        if ("meta_event".equals(postType)) {
            String metaType = safeEventName(event.path("meta_event_type").asText(""));
            if ("heartbeat".equals(metaType)) {
                emit(AdapterEvent.Type.HEARTBEAT, "meta_event_type=heartbeat");
            } else {
                emit(AdapterEvent.Type.IGNORED_EVENT, "meta_event_type=" + metaType);
            }
            return;
        }
        emit(AdapterEvent.Type.IGNORED_EVENT, "post_type=" + postType);
    }

    private void handleMessageEvent(JsonNode event) {
        Optional<InboundMessage> decoded =
                new OneBot11Codec().decodeMessage(adapterId, event, Instant.now());
        if (decoded.isEmpty()) {
            emit(AdapterEvent.Type.IGNORED_EVENT, "message_without_supported_text");
            return;
        }
        InboundMessage message = decoded.get();
        if (config.suppressSelfMessages() && message.selfId().equals(message.userId())) {
            emit(AdapterEvent.Type.IGNORED_EVENT, "self_message_suppressed");
            return;
        }
        if (!deduplicator.markIfNew(message.dedupeKey(), System.nanoTime())) {
            emit(AdapterEvent.Type.IGNORED_EVENT, "duplicate_message");
            return;
        }
        InboundHandler handler = inboundHandler.get();
        if (handler == null) {
            return;
        }
        try {
            handler.onMessage(message);
        } catch (RuntimeException exception) {
            emit(AdapterEvent.Type.ERROR, "INBOUND_HANDLER_FAILED");
        }
    }

    private void emit(AdapterEvent.Type type, String detail) {
        AdapterEvent event = new AdapterEvent(type, Instant.now(),
                Redaction.sanitize(detail, config.accessToken().orElse(null)));
        if (type == AdapterEvent.Type.HEARTBEAT) {
            OpsLog.debug("event=adapter_event type={} detail={}", type, event.detail());
        } else {
            OpsLog.info("event=adapter_event type={} detail={}", type, event.detail());
        }
        for (AdapterEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                LOG.warn("NapCat adapter event listener failed: {}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void updateStatus(ConnectionState state, String errorCode, String errorMessage) {
        status.set(new AdapterStatus(
                state,
                Instant.now(),
                errorCode == null ? null : Redaction.sanitize(errorCode, config.accessToken().orElse(null)),
                errorMessage == null ? null : Redaction.sanitize(errorMessage, config.accessToken().orElse(null))));
    }

    private static String safeEventName(String value) {
        if (value != null && SAFE_EVENT_NAME.matcher(value).matches()) {
            return value;
        }
        return "unknown";
    }

    private static String scalarText(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String result = value.asText();
        return result == null || result.isBlank() ? null : result;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class EventListener implements WebSocket.Listener {
        private final StringBuilder frameBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (closed.get()) {
                webSocket.abort();
                return;
            }
            WebSocket old = NapCatAdapter.this.webSocket.getAndSet(webSocket);
            if (old != null && old != webSocket) {
                old.abort();
            }
            reconnectAttempts.set(0);
            reconnectScheduled.set(false);
            updateStatus(ConnectionState.CONNECTED, null, null);
            emit(AdapterEvent.Type.CONNECTED, "event websocket connected");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frameBuffer.append(data);
            if (last) {
                String payload = frameBuffer.toString();
                frameBuffer.setLength(0);
                handleFrame(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closed.get()) {
                return null;
            }
            if (!NapCatAdapter.this.webSocket.compareAndSet(webSocket, null)) {
                return null;
            }
            updateStatus(
                    ConnectionState.RECONNECTING,
                    "WS_DISCONNECTED",
                    "event websocket closed, status=" + statusCode);
            emit(AdapterEvent.Type.DISCONNECTED, "ws closed, status=" + statusCode);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closed.get()) {
                return;
            }
            if (NapCatAdapter.this.webSocket.get() != webSocket) {
                return;
            }
            Throwable cause = unwrap(error);
            String summary = Redaction.errorSummary(cause, config.accessToken().orElse(null));
            updateStatus(ConnectionState.RECONNECTING, "WS_ERROR", summary);
            emit(AdapterEvent.Type.ERROR, "WS_ERROR: " + summary);
            webSocket.abort();
            scheduleReconnect();
        }
    }

    private record PreparedSendAction(String action, ObjectNode payload) {
    }

    private static final class SendOperation {
        private final OutboundMessage message;
        private final List<List<OutboundSegment>> chunks;
        private final List<String> messageIds = new CopyOnWriteArrayList<>();
        private final AtomicBoolean requestEverStarted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CompletableFuture<SendReceipt> result = new CompletableFuture<>();

        private SendOperation(OutboundMessage message, List<List<OutboundSegment>> chunks) {
            this.message = message;
            this.chunks = List.copyOf(chunks);
        }
    }

    private enum FailureKind {
        TRANSPORT,
        HTTP_STATUS,
        INVALID_RESPONSE,
        REJECTED
    }

    private static final class OneBotActionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final FailureKind kind;
        private final String errorCode;

        private OneBotActionException(FailureKind kind, String errorCode, String message) {
            super(message);
            this.kind = kind;
            this.errorCode = errorCode;
        }
    }
}
