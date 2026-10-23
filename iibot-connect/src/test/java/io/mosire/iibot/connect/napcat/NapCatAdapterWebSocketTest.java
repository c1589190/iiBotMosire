package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.iibot.connect.core.AdapterEvent;
import io.mosire.iibot.connect.core.AdapterStatus.ConnectionState;
import io.mosire.iibot.connect.core.InboundMessage;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

class NapCatAdapterWebSocketTest {

    @Test
    void receivesEventsDeduplicatesAndReconnectsAfterServerClose() throws Exception {
        MockNapCatWsServer server = new MockNapCatWsServer();
        server.start();
        assertThat(server.awaitStarted(Duration.ofSeconds(2))).isTrue();

        NapCatConfig config = NapCatConfig.builder(
                        URI.create("ws://127.0.0.1:" + server.getPort() + "/"),
                        URI.create("http://127.0.0.1:1/"))
                .accessToken("test-secret")
                .accountId("10001")
                .connectTimeout(Duration.ofMillis(500))
                .requestTimeout(Duration.ofSeconds(1))
                .initialReconnectDelay(Duration.ofMillis(50))
                .maxReconnectDelay(Duration.ofMillis(120))
                .reconnectJitter(0.0)
                .dedupeTtl(Duration.ofMinutes(1))
                .rateLimitPerSecond(1_000)
                .rateLimitBurst(1_000)
                .wsAuthMode(NapCatConfig.WsAuthMode.BOTH)
                .build();

        NapCatAdapter adapter = new NapCatAdapter(config);
        CountDownLatch inboundLatch = new CountDownLatch(1);
        AtomicInteger inboundCount = new AtomicInteger();
        AtomicReference<InboundMessage> received = new AtomicReference<>();
        List<AdapterEvent.Type> eventTypes = new CopyOnWriteArrayList<>();
        adapter.addEventListener(event -> eventTypes.add(event.type()));
        try {
            adapter.start(message -> {
                received.set(message);
                inboundCount.incrementAndGet();
                inboundLatch.countDown();
            });

            assertThat(await(() -> server.openCount.get() == 1, Duration.ofSeconds(3))).isTrue();
            assertThat(await(() -> adapter.status().state() == ConnectionState.CONNECTED,
                    Duration.ofSeconds(3))).isTrue();
            assertThat(server.authorization.get()).isEqualTo("Bearer test-secret");
            assertThat(server.resourceDescriptor.get()).contains("access_token=test-secret");

            String messageEvent = """
                    {"time":1700000000,"self_id":10001,"post_type":"message",
                     "message_type":"private","message_id":777,"user_id":20002,
                     "message":[{"type":"text","data":{"text":"hello ws"}}]}
                    """;
            WebSocket connection = server.firstConnection.get();
            connection.send(messageEvent);
            connection.send(messageEvent);

            assertThat(inboundLatch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200L);
            assertThat(inboundCount.get()).isEqualTo(1);
            assertThat(received.get().text()).isEqualTo("hello ws");
            assertThat(received.get().dedupeKey()).isEqualTo("self:10001:private:msg:777");

            connection.send("""
                    {"time":1700000001,"self_id":10001,"post_type":"meta_event",
                     "meta_event_type":"heartbeat","interval":30000}
                    """);
            connection.send("""
                    {"time":1700000002,"self_id":10001,"post_type":"notice",
                     "notice_type":"group_increase"}
                    """);
            assertThat(await(() -> eventTypes.contains(AdapterEvent.Type.HEARTBEAT),
                    Duration.ofSeconds(2))).isTrue();
            assertThat(eventTypes).contains(AdapterEvent.Type.IGNORED_EVENT);

            int opensBeforeReconnect = server.openCount.get();
            connection.close(1000, "contract-test-reconnect");
            assertThat(await(() -> server.openCount.get() > opensBeforeReconnect,
                    Duration.ofSeconds(4))).isTrue();
            assertThat(await(() -> adapter.status().state() == ConnectionState.CONNECTED,
                    Duration.ofSeconds(4))).isTrue();
            assertThat(eventTypes).contains(AdapterEvent.Type.DISCONNECTED);
            assertThat(eventTypes).contains(AdapterEvent.Type.RECONNECT_SCHEDULED);
            assertThat(eventTypes).contains(AdapterEvent.Type.CONNECTED);
        } finally {
            adapter.close();
            int opensAtClose = server.openCount.get();
            Thread.sleep(250L);
            assertThat(server.openCount.get()).isEqualTo(opensAtClose);
            assertThat(adapter.status().state()).isEqualTo(ConnectionState.CLOSED);
            server.stop(100);
        }
    }

    @Test
    void connectionFailureIsObservableAndDoesNotLeakToken() throws Exception {
        NapCatConfig config = NapCatConfig.builder(
                        URI.create("ws://127.0.0.1:1/?access_token=embedded-secret"),
                        URI.create("http://127.0.0.1:1/"))
                .accessToken("embedded-secret")
                .connectTimeout(Duration.ofMillis(150))
                .requestTimeout(Duration.ofMillis(200))
                .initialReconnectDelay(Duration.ofMillis(40))
                .maxReconnectDelay(Duration.ofMillis(80))
                .reconnectJitter(0.0)
                .rateLimitPerSecond(1_000)
                .rateLimitBurst(1_000)
                .build();
        NapCatAdapter adapter = new NapCatAdapter(config);
        List<AdapterEvent> events = new CopyOnWriteArrayList<>();
        adapter.addEventListener(events::add);
        try {
            adapter.start(message -> {
            });
            assertThat(await(() -> events.stream()
                            .anyMatch(event -> event.type() == AdapterEvent.Type.RECONNECT_SCHEDULED),
                    Duration.ofSeconds(3))).isTrue();
            assertThat(adapter.status().optionalLastErrorMessage().orElse(""))
                    .doesNotContain("embedded-secret");
            assertThat(events)
                    .allSatisfy(event -> assertThat(event.detail()).doesNotContain("embedded-secret"));
        } finally {
            adapter.close();
        }
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

    private static final class MockNapCatWsServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicReference<WebSocket> firstConnection = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> resourceDescriptor = new AtomicReference<>();

        private MockNapCatWsServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setDaemon(true);
        }

        private boolean awaitStarted(Duration timeout) throws InterruptedException {
            return started.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            firstConnection.compareAndSet(null, connection);
            authorization.compareAndSet(null, handshake.getFieldValue("Authorization"));
            resourceDescriptor.compareAndSet(null, handshake.getResourceDescriptor());
            openCount.incrementAndGet();
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }
}
