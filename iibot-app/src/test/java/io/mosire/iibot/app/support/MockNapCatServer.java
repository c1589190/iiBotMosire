package io.mosire.iibot.app.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地 Mock NapCat：JDK {@link HttpServer} 处理 OneBot 11 HTTP 动作，Java-WebSocket 提供事件推送。
 *
 * <p>只绑定 {@code 127.0.0.1} 的随机端口（端口 0），不连接真实 QQ、不访问外网。HTTP 侧记录每个
 * 请求的路径、查询串、{@code Authorization} 头与 JSON 负载；WS 侧记录握手 {@code Authorization}
 * 与资源描述（含 query token），并允许测试向已连接客户端广播 OneBot 事件。</p>
 */
public final class MockNapCatServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SEND_MSG = "/send_msg";
    private static final String SEND_PRIVATE_MSG = "/send_private_msg";
    private static final String SEND_GROUP_MSG = "/send_group_msg";
    private static final String GET_LOGIN_INFO = "/get_login_info";

    private final HttpServer httpServer;
    private final WsServer wsServer;
    private final List<HttpAction> actions = new CopyOnWriteArrayList<>();
    private final AtomicInteger messageIdSequence = new AtomicInteger();

    public MockNapCatServer() throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.httpServer.createContext(SEND_MSG, exchange -> handleAction(exchange, true));
        this.httpServer.createContext(SEND_PRIVATE_MSG, exchange -> handleAction(exchange, true));
        this.httpServer.createContext(SEND_GROUP_MSG, exchange -> handleAction(exchange, true));
        this.httpServer.createContext(GET_LOGIN_INFO, exchange -> handleAction(exchange, false));
        this.httpServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-napcat-http");
            thread.setDaemon(true);
            return thread;
        }));
        this.httpServer.start();

        this.wsServer = new WsServer();
        this.wsServer.start();
        try {
            if (!this.wsServer.awaitStarted(Duration.ofSeconds(5))) {
                throw new IllegalStateException("Mock NapCat WS server 启动超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock NapCat WS server 启动被中断", interrupted);
        }
    }

    /** HTTP 动作基础 URI，形如 {@code http://127.0.0.1:<port>/}。 */
    public URI httpBaseUri() {
        return URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/");
    }

    /** 事件 WS URI，形如 {@code ws://127.0.0.1:<port>/}。 */
    public URI eventWebSocketUri() {
        return URI.create("ws://127.0.0.1:" + wsServer.getPort() + "/");
    }

    /** 等待真实 NapCatAdapter 建立事件 WS 连接。 */
    public boolean awaitWebSocketConnection(Duration timeout) throws InterruptedException {
        return wsServer.awaitConnection(timeout);
    }

    /** 记录到的 WS 握手 {@code Authorization} 头；无连接时为 {@code null}。 */
    public String wsAuthorization() {
        return wsServer.authorization.get();
    }

    /** 记录到的 WS 握手资源描述（含 query）；无连接时为 {@code null}。 */
    public String wsResourceDescriptor() {
        return wsServer.resourceDescriptor.get();
    }

    /** 向当前所有已连接客户端广播一条 OneBot 事件 JSON。 */
    public void broadcastEvent(String json) {
        wsServer.broadcast(json);
    }

    /** 仅发送类 HTTP 动作（{@code /send_msg}、{@code /send_private_msg}、{@code /send_group_msg}）。 */
    public List<HttpAction> sendActions() {
        return actions.stream().filter(HttpAction::isSendAction).toList();
    }

    /** 记录到的全部 HTTP 动作（含 {@code /get_login_info}）。 */
    public List<HttpAction> allActions() {
        return List.copyOf(actions);
    }

    private void handleAction(HttpExchange exchange, boolean sendAction) throws IOException {
        try {
            JsonNode payload;
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length == 0) {
                payload = MAPPER.createObjectNode();
            } else {
                payload = MAPPER.readTree(body);
            }
            actions.add(new HttpAction(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    payload));
            String response;
            if (sendAction) {
                int messageId = messageIdSequence.incrementAndGet();
                response = "{\"status\":\"ok\",\"retcode\":0,\"data\":{\"message_id\":"
                        + messageId + "}}";
            } else {
                response = "{\"status\":\"ok\",\"retcode\":0,"
                        + "\"data\":{\"user_id\":\"bot-1\",\"nickname\":\"mock-napcat\"}}";
            }
            respond(exchange, 200, response);
        } catch (RuntimeException failure) {
            respond(exchange, 500, "{\"status\":\"failed\",\"retcode\":-1,\"data\":null}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        try {
            wsServer.stop(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        httpServer.stop(0);
    }

    /**
     * 一次 OneBot 11 HTTP 动作记录。
     *
     * @param path          HTTP 路径
     * @param query         原始查询串；无则为 null
     * @param authorization {@code Authorization} 头；无则为 null
     * @param payload       请求 JSON
     */
    public record HttpAction(String path, String query, String authorization, JsonNode payload) {

        public boolean isSendAction() {
            return SEND_MSG.equals(path)
                    || SEND_PRIVATE_MSG.equals(path)
                    || SEND_GROUP_MSG.equals(path);
        }

        /** 目标是否为群：DEDICATED 模式看路径，SEND_MSG 模式看 message_type。 */
        public boolean isGroupTarget() {
            return SEND_GROUP_MSG.equals(path)
                    || "group".equals(payload.path("message_type").asText());
        }

        /** 目标是否为私聊：DEDICATED 模式看路径，SEND_MSG 模式看 message_type。 */
        public boolean isPrivateTarget() {
            return SEND_PRIVATE_MSG.equals(path)
                    || "private".equals(payload.path("message_type").asText());
        }

        /** 拼接 message 数组中所有 text 段的文本。 */
        public String messageText() {
            JsonNode message = payload.path("message");
            if (!message.isArray()) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode segment : message) {
                if ("text".equals(segment.path("type").asText())) {
                    text.append(segment.path("data").path("text").asText());
                }
            }
            return text.toString();
        }
    }

    private final class WsServer extends WebSocketServer {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch connected = new CountDownLatch(1);
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> resourceDescriptor = new AtomicReference<>();

        private WsServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setDaemon(true);
        }

        private boolean awaitStarted(Duration timeout) throws InterruptedException {
            return started.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private boolean awaitConnection(Duration timeout) throws InterruptedException {
            return connected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            authorization.compareAndSet(null, handshake.getFieldValue("Authorization"));
            resourceDescriptor.compareAndSet(null, handshake.getResourceDescriptor());
            connected.countDown();
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            // 测试关闭时无需额外处理。
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            // Mock 事件通道不接收客户端业务消息。
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
            // 连接关闭/重连过程中的错误由测试侧状态轮询兜底。
        }
    }
}
