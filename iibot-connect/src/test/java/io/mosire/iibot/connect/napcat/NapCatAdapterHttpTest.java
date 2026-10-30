package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class NapCatAdapterHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsPrivateTextWithBearerAndParsesSuccessReceipt() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            SendReceipt receipt = adapter.send(OutboundMessage.privateMessage(
                            "20002", List.of("hello"), "idem-success"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(receipt.adapterMessageIds()).containsExactly("1");
            assertThat(server.authorizationHeaders).contains("Bearer secret-token");
            assertThat(server.paths).containsExactly("/send_msg");
            assertThat(server.texts).containsExactly("hello");
            JsonNode payload = server.sendPayloads.getFirst();
            assertThat(payload.path("message_type").asText()).isEqualTo("private");
            assertThat(payload.path("user_id").asText()).isEqualTo("20002");
            assertThat(payload.path("message").get(0).path("type").asText()).isEqualTo("text");
            assertThat(payload.path("message").get(0).path("data").path("text").asText())
                    .isEqualTo("hello");
        }
    }

    @Test
    void privateTemporaryMessageUsesDedicatedActionAndGroupId() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofSeconds(2), 100))) {
            SendReceipt receipt = adapter.send(OutboundMessage.privateTemporaryMessage(
                            "3638514187", "1104560326", List.of("hello"), "idem-temp"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(server.paths).containsExactly("/send_private_msg");
            JsonNode payload = server.sendPayloads.getFirst();
            assertThat(payload.path("user_id").asText()).isEqualTo("3638514187");
            assertThat(payload.path("group_id").asText()).isEqualTo("1104560326");
            assertThat(payload.has("message_type")).isFalse();
        }
    }

    @Test
    void getLoginInfoUsesHttpActionAndBearerAuth() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            LoginInfo info = adapter.getLoginInfo().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(info.userId()).isEqualTo("123123");
            assertThat(info.nickname()).isEqualTo("mock-napcat");
            assertThat(server.authorizationHeaders).containsExactly("Bearer secret-token");
            assertThat(server.paths).containsExactly("/get_login_info");
        }
    }

    @Test
    void accountInfoMapsLoginInfoThroughStandardProvider() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            AccountInfo info = adapter.accountInfo().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(info.accountId()).isEqualTo("123123");
            assertThat(info.nickname()).isEqualTo("mock-napcat");
            assertThat(info.displayName()).isEqualTo("mock-napcat");
            assertThat(server.paths).containsExactly("/get_login_info");
            assertThat(server.loginCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void readsFriendHistoryWithSegmentsAndUsesMessageSeqCursor() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            server.historyMessages = 1;
            ChatHistoryPage page = adapter.history(new ChatHistoryQuery(
                            SessionType.PRIVATE, "20002", null, 2, true))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(server.historyPaths).containsExactly("/get_friend_msg_history");
            JsonNode payload = server.historyPayloads.getFirst();
            assertThat(payload.path("user_id").asText()).isEqualTo("20002");
            assertThat(payload.path("count").asInt()).isEqualTo(2);
            assertThat(payload.path("reverse_order").asBoolean()).isTrue();
            assertThat(payload.path("parse_mult_msg").asBoolean()).isTrue();
            assertThat(payload.path("disable_get_url").asBoolean()).isFalse();

            assertThat(page.messages()).hasSize(2);
            assertThat(page.messages().getFirst().messageId()).isEqualTo("101");
            assertThat(page.messages().getFirst().senderId()).isEqualTo("20002");
            assertThat(page.messages().getFirst().senderName()).isEqualTo("测试玩家");
            assertThat(page.messages().getFirst().text()).isEqualTo("历史文本");
            assertThat(page.messages().get(1).segments()).singleElement()
                    .extracting(segment -> segment.type().name()).isEqualTo("IMAGE");
            assertThat(page.exhausted()).isFalse();
            assertThat(page.nextCursor()).isEqualTo("100");
        }
    }

    @Test
    void readsGroupHistoryWithGroupIdAndReturnsNextCursorForFullPage() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            server.historyMessages = 2;
            ChatHistoryPage page = adapter.history(new ChatHistoryQuery(
                            SessionType.GROUP, "30003", null, 2, true))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(server.historyPaths).containsExactly("/get_group_msg_history");
            assertThat(server.historyPayloads.getFirst().path("group_id").asText())
                    .isEqualTo("30003");
            assertThat(page.messages()).hasSize(2);
            assertThat(page.exhausted()).isFalse();
            assertThat(page.nextCursor()).isEqualTo("100");
        }
    }

    @Test
    void historyRetcode1200IsAnEmptyExhaustedPageAndNotRetried() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            server.historyRetcode = 1200;

            ChatHistoryPage page = adapter.history(new ChatHistoryQuery(
                            SessionType.PRIVATE, "20002", "old-cursor", 5, true))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(page.messages()).isEmpty();
            assertThat(page.exhausted()).isTrue();
            assertThat(page.nextCursor()).isNull();
            assertThat(server.historyCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void accountInfoRetcodeFailureIsExceptionalAndNeverRetried() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            server.loginRetcode = 100;

            assertThatThrownBy(() -> adapter.accountInfo()
                            .toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .satisfies(error -> {
                        assertThat(causeChainText(error)).doesNotContain("secret-token", "http://", "https://");
                    });
            assertThat(server.loginCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void accountInfoHttpErrorIsExceptionalAndNeverRetried() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            server.loginStatus = 500;

            assertThatThrownBy(() -> adapter.accountInfo()
                            .toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .satisfies(error -> assertThat(causeChainText(error))
                            .doesNotContain("secret-token", "http://", "https://"));
            assertThat(server.loginCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void accountInfoTimeoutIsExceptionalAndNeverRetried() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofMillis(120), 100))) {
            server.loginDelayMillis = 800L;

            assertThatThrownBy(() -> adapter.accountInfo()
                            .toCompletableFuture().get(3, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .satisfies(error -> assertThat(causeChainText(error))
                            .doesNotContain("secret-token", "http://", "https://"));
            assertThat(server.loginCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void retcodeFailureIsExplicitFailedReceipt() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            server.retcode = 100;

            SendReceipt receipt = adapter.send(OutboundMessage.privateMessage(
                            "20002", List.of("hello"), "idem-retcode"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(receipt.errorCode()).isEqualTo("ONEBOT_RETCODE_100");
            assertThat(receipt.adapterMessageIds()).isEmpty();
            assertThat(receipt.detail()).doesNotContain("secret-token");
        }
    }

    @Test
    void requestTimeoutIsUnknownAndNeverAutoRetried() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofMillis(120), 100))) {
            server.sendDelayMillis = 800L;

            SendReceipt receipt = adapter.send(OutboundMessage.privateMessage(
                            "20002", List.of("hello"), "idem-timeout"))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.UNKNOWN);
            assertThat(receipt.errorCode()).isEqualTo("HTTP_IO_ERROR");
            assertThat(receipt.detail()).doesNotContain("secret-token");
            assertThat(server.sendCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void splitsLongTextAndReturnsAllMessageIdsInOrder() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 5))) {
            SendReceipt receipt = adapter.send(OutboundMessage.groupMessage(
                            "30003", List.of("abcdefghij"), "idem-split"))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(receipt.adapterMessageIds()).containsExactly("1", "2");
            assertThat(server.texts).containsExactly("abcde", "fghij");
            assertThat(server.sendPayloads.get(0).path("message_type").asText()).isEqualTo("group");
            assertThat(server.sendPayloads.get(0).path("group_id").asText()).isEqualTo("30003");
        }
    }

    @Test
    void sendsRichReplyMentionAndTextSegmentsInOrder() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofSeconds(2), 100))) {
            SendReceipt receipt = adapter.send(OutboundMessage.richGroupMessage(
                            "30003",
                            List.of(new OutboundSegment.Reply("m-1"),
                                    new OutboundSegment.Mention("20002"),
                                    new OutboundSegment.Text("收到")),
                            "idem-rich"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            JsonNode message = server.sendPayloads.getFirst().path("message");
            assertThat(message).hasSize(3);
            assertThat(message.get(0).path("type").asText()).isEqualTo("reply");
            assertThat(message.get(0).path("data").path("id").asText()).isEqualTo("m-1");
            assertThat(message.get(1).path("type").asText()).isEqualTo("at");
            assertThat(message.get(1).path("data").path("qq").asText()).isEqualTo("20002");
            assertThat(message.get(2).path("type").asText()).isEqualTo("text");
            assertThat(message.get(2).path("data").path("text").asText()).isEqualTo("收到");
        }
    }

    @Test
    void sendsMentionAllAsStandardAtAllSegment() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofSeconds(2), 100))) {
            SendReceipt receipt = adapter.send(OutboundMessage.richGroupMessage(
                            "30003",
                            List.of(new OutboundSegment.MentionAll(),
                                    new OutboundSegment.Text("全员通知")),
                            "idem-all"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            JsonNode message = server.sendPayloads.getFirst().path("message");
            assertThat(message).hasSize(2);
            assertThat(message.get(0).path("type").asText()).isEqualTo("at");
            assertThat(message.get(0).path("data").path("qq").asText()).isEqualTo("all");
            assertThat(message.get(1).path("type").asText()).isEqualTo("text");
        }
    }

    @Test
    void sendsImageAndStickerAsStandardSegments() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofSeconds(2), 100))) {
            SendReceipt receipt = adapter.send(OutboundMessage.richGroupMessage(
                            "30003",
                            List.of(new OutboundSegment.Image(
                                            "asset-1", "image.png", "image/png",
                                            new byte[]{1, 2, 3}),
                                    new OutboundSegment.Sticker("1"),
                                    new OutboundSegment.Text("看图")),
                            "idem-image-sticker"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            JsonNode message = server.sendPayloads.getFirst().path("message");
            assertThat(message).hasSize(3);
            assertThat(message.get(0).path("type").asText()).isEqualTo("image");
            assertThat(message.get(0).path("data").path("file").asText())
                    .isEqualTo("base64://AQID");
            assertThat(message.get(1).path("type").asText()).isEqualTo("face");
            assertThat(message.get(1).path("data").path("id").asText()).isEqualTo("1");
            assertThat(message.get(2).path("type").asText()).isEqualTo("text");
        }
    }

    @Test
    void dedicatedActionModeUsesPrivateAndGroupEndpoints() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(
                     config(server, Duration.ofSeconds(2), 100,
                             NapCatConfig.SendActionMode.DEDICATED))) {
            SendReceipt privateReceipt = adapter.send(OutboundMessage.privateMessage(
                            "20002", List.of("private"), "idem-private"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            SendReceipt groupReceipt = adapter.send(OutboundMessage.groupMessage(
                            "30003", List.of("group"), "idem-group"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(privateReceipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(groupReceipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(server.paths)
                    .containsExactly("/send_private_msg", "/send_group_msg");
            assertThat(server.sendPayloads.get(0).path("user_id").asText()).isEqualTo("20002");
            assertThat(server.sendPayloads.get(0).has("message_type")).isFalse();
            assertThat(server.sendPayloads.get(1).path("group_id").asText()).isEqualTo("30003");
            assertThat(server.sendPayloads.get(1).has("message_type")).isFalse();
        }
    }

    @Test
    void sameIdempotencyKeyReusesReceiptAndSendsOnce() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(2), 100))) {
            OutboundMessage message = OutboundMessage.privateMessage(
                    "20002", List.of("hello"), "idem-repeat");

            SendReceipt first = adapter.send(message).toCompletableFuture().get(2, TimeUnit.SECONDS);
            SendReceipt second = adapter.send(message).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(second).isSameAs(first);
            assertThat(server.sendCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void rejectsEmptyTextBeforeSending() {
        assertThatThrownBy(() -> OutboundMessage.privateMessage(
                "20002", List.of(""), "idem-empty"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空文本");
        assertThatThrownBy(() -> OutboundMessage.privateMessage(
                "20002", List.of(), "idem-empty-list"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeCompletesInFlightSendAsUnknown() throws Exception {
        MockHttpServer server = new MockHttpServer();
        NapCatAdapter adapter = new NapCatAdapter(config(server, Duration.ofSeconds(5), 100));
        try {
            server.sendDelayMillis = 700L;
            var future = adapter.send(OutboundMessage.privateMessage(
                    "20002", List.of("hello"), "idem-close")).toCompletableFuture();

            assertThat(await(() -> server.sendCalls.get() == 1, Duration.ofSeconds(2))).isTrue();
            adapter.close();
            SendReceipt receipt = future.get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.UNKNOWN);
            assertThat(receipt.errorCode()).isEqualTo("ADAPTER_CLOSED_DURING_SEND");
        } finally {
            adapter.close();
            server.close();
        }
    }

    private static NapCatConfig config(MockHttpServer server, Duration requestTimeout, int maxTextLength) {
        return config(server, requestTimeout, maxTextLength, NapCatConfig.SendActionMode.SEND_MSG);
    }

    private static NapCatConfig config(
            MockHttpServer server,
            Duration requestTimeout,
            int maxTextLength,
            NapCatConfig.SendActionMode sendActionMode) {
        return NapCatConfig.builder(
                        URI.create("ws://127.0.0.1:1/"),
                        server.baseUri())
                .accessToken("secret-token")
                .accountId("10001")
                .connectTimeout(Duration.ofMillis(200))
                .requestTimeout(requestTimeout)
                .maxTextLength(maxTextLength)
                .sendActionMode(sendActionMode)
                .initialReconnectDelay(Duration.ofMillis(50))
                .maxReconnectDelay(Duration.ofMillis(100))
                .reconnectJitter(0.0)
                .dedupeTtl(Duration.ofMinutes(1))
                .rateLimitPerSecond(1_000)
                .rateLimitBurst(1_000)
                .build();
    }

    private static String causeChainText(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            text.append(current.getClass().getName()).append(' ')
                    .append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        return text.toString();
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private static final class MockHttpServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<JsonNode> sendPayloads = new CopyOnWriteArrayList<>();
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private final AtomicInteger loginCalls = new AtomicInteger();
        private final AtomicInteger historyCalls = new AtomicInteger();
        private final List<String> historyPaths = new CopyOnWriteArrayList<>();
        private final List<JsonNode> historyPayloads = new CopyOnWriteArrayList<>();
        private volatile int retcode;
        private volatile int historyRetcode;
        private volatile int historyMessages = 2;
        private volatile int loginRetcode;
        private volatile int loginStatus = 200;
        private volatile long sendDelayMillis;
        private volatile long loginDelayMillis;

        private MockHttpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/send_msg", this::handleSend);
            server.createContext("/send_private_msg", this::handleSend);
            server.createContext("/send_group_msg", this::handleSend);
            server.createContext("/get_login_info", this::handleLoginInfo);
            server.createContext("/get_friend_msg_history", this::handleHistory);
            server.createContext("/get_group_msg_history", this::handleHistory);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mock-napcat-http");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private void handleSend(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            JsonNode payload = MAPPER.readTree(requestBody);
            sendPayloads.add(payload);
            texts.add(payload.path("message").get(0).path("data").path("text").asText());
            int messageId = sendCalls.incrementAndGet();
            sleepIfRequested();
            respond(exchange, 200, "{\"status\":\"ok\",\"retcode\":" + retcode
                    + ",\"data\":{\"message_id\":" + messageId + "}}");
        }

        private void handleHistory(HttpExchange exchange) throws IOException {
            historyPaths.add(exchange.getRequestURI().getPath());
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            historyPayloads.add(MAPPER.readTree(requestBody));
            historyCalls.incrementAndGet();
            String messages = historyMessages >= 2
                    ? "[{\"message_id\":101,\"message_seq\":101,\"time\":1700000000,\"sender\":{\"user_id\":20002,\"nickname\":\"测试玩家\"},\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"历史文本\"}}]},"
                    + "{\"message_id\":102,\"message_seq\":102,\"time\":1700000001,\"sender\":{\"user_id\":20002,\"nickname\":\"测试玩家\"},\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"第二条\"}}]}]"
                    : "[{\"message_id\":101,\"message_seq\":101,\"time\":1700000000,\"sender\":{\"user_id\":20002,\"nickname\":\"测试玩家\"},\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"历史文本\"}}]},"
                    + "{\"message_id\":102,\"message_seq\":102,\"time\":1700000001,\"sender\":{\"user_id\":20002,\"nickname\":\"测试玩家\"},\"message\":[{\"type\":\"image\",\"data\":{}}]}]";
            respond(exchange, 200,
                    "{\"status\":\"ok\",\"retcode\":" + historyRetcode
                            + ",\"data\":{\"messages\":" + messages + "}}");
        }

        private void handleLoginInfo(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            loginCalls.incrementAndGet();
            sleepMillis(loginDelayMillis);
            if (loginStatus != 200) {
                respond(exchange, loginStatus, "{\"status\":\"failed\"}");
                return;
            }
            respond(exchange, 200,
                    "{\"status\":\"ok\",\"retcode\":" + loginRetcode + ","
                            + "\"data\":{\"user_id\":123123,\"nickname\":\"mock-napcat\"}}");
        }

        private void sleepMillis(long delayMillis) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void sleepIfRequested() {
            if (sendDelayMillis > 0) {
                try {
                    Thread.sleep(sendDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
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
            server.stop(0);
        }
    }
}
