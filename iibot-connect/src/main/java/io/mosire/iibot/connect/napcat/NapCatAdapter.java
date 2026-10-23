package io.mosire.iibot.connect.napcat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AdapterEvent;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.AdapterStatus.ConnectionState;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
public final class NapCatAdapter implements MessagingAdapter, AccountInfoProvider {

    private static final Logger LOG = LoggerFactory.getLogger(NapCatAdapter.class);
    private static final Pattern SAFE_EVENT_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final NapCatConfig config;
    private final String adapterId;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final RecentMessageDeduplicator deduplicator;
    private final TargetRateLimiter rateLimiter;

    private final AtomicReference<InboundHandler> inboundHandler = new AtomicReference<>();
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
        updateStatus(ConnectionState.CONNECTING, null, null);
        connect();
    }

    @Override
    public CompletionStage<SendReceipt> send(OutboundMessage message) {
        if (message == null) {
            return CompletableFuture.completedFuture(
                    SendReceipt.failed("INVALID_MESSAGE", "发送消息不能为 null"));
        }
        if (message.textSegments().isEmpty()
                || message.textSegments().stream().allMatch(String::isBlank)) {
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
        updateStatus(ConnectionState.CLOSED, null, null);
        emit(AdapterEvent.Type.DISCONNECTED, "adapter closed");
        scheduler.shutdownNow();
    }

    private CompletableFuture<SendReceipt> executeSend(OutboundMessage message) {
        List<String> chunks;
        try {
            chunks = TextSplitter.split(message.textSegments(), config.maxTextLength());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(SendReceipt.failed(
                    "INVALID_TEXT",
                    Redaction.sanitize(exception.getMessage(), config.accessToken().orElse(null))));
        }
        SendOperation operation = new SendOperation(message, chunks);
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

    private SendReceipt failureReceipt(SendOperation operation, Throwable throwable) {
        if (throwable instanceof OneBotActionException actionException) {
            if (actionException.kind == FailureKind.REJECTED) {
                if (operation.messageIds.isEmpty()) {
                    return SendReceipt.failed(
                            actionException.errorCode,
                            "服务端明确拒绝发送");
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

    private PreparedSendAction prepareSendAction(OutboundMessage message, String text) {
        boolean privateTarget = message.targetType() == OutboundMessage.TargetType.PRIVATE;
        String action;
        ObjectNode payload = objectMapper.createObjectNode();
        if (config.sendActionMode() == NapCatConfig.SendActionMode.DEDICATED) {
            action = privateTarget ? "send_private_msg" : "send_group_msg";
            payload.put(privateTarget ? "user_id" : "group_id", message.targetId());
        } else {
            action = "send_msg";
            payload.put("message_type", privateTarget ? "private" : "group");
            payload.put(privateTarget ? "user_id" : "group_id", message.targetId());
        }
        ObjectNode segment = objectMapper.createObjectNode();
        segment.put("type", "text");
        segment.putObject("data").put("text", text);
        ArrayNode content = objectMapper.createArrayNode();
        content.add(segment);
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
                        throw new CompletionException(new OneBotActionException(
                                FailureKind.REJECTED,
                                "ONEBOT_RETCODE_" + retcode,
                                "OneBot 动作被服务端拒绝"));
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
        private final List<String> chunks;
        private final List<String> messageIds = new CopyOnWriteArrayList<>();
        private final AtomicBoolean requestEverStarted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CompletableFuture<SendReceipt> result = new CompletableFuture<>();

        private SendOperation(OutboundMessage message, List<String> chunks) {
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
