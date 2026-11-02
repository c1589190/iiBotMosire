package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.iibot.connect.core.GroupSystemRequest;
import io.mosire.iibot.connect.core.SendReceipt;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 群系统请求（邀请/入群申请）读写与邀请接受回退链。 */
class NapCatAdapterGroupRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsInvitedAndJoinRequestsPendingFirst() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            server.systemMsgBody = "{\"invited_requests\":["
                    + "{\"request_id\":1793612836122334,\"invitor_uin\":123456,"
                    + "\"invitor_nick\":\"邀请人\",\"group_id\":938269591,"
                    + "\"group_name\":\"目标群\",\"message\":\"来吧\",\"checked\":false}],"
                    + "\"join_requests\":["
                    + "{\"request_id\":1792839371808078,\"requester_uin\":3341796326,"
                    + "\"requester_nick\":\"二呱\",\"group_id\":1104560326,"
                    + "\"group_name\":\"V17Levant交流群\",\"checked\":true},"
                    + "{\"request_id\":1792837085640003,\"requester_uin\":3550477368,"
                    + "\"requester_nick\":\"希望永恒\",\"group_id\":1104560326,"
                    + "\"group_name\":\"V17Levant交流群\",\"checked\":false}]}";

            List<GroupSystemRequest> requests = adapter.listGroupRequests(50)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(requests).hasSize(3);
            assertThat(server.paths).containsExactly("/get_group_system_msg");
            GroupSystemRequest first = requests.getFirst();
            assertThat(first.kind()).isEqualTo(GroupSystemRequest.Kind.INVITE);
            assertThat(first.eventType()).isEqualTo(GroupSystemRequest.EVENT_TYPE_INVITE);
            assertThat(first.sequence()).isEqualTo(1793612836122334L);
            assertThat(first.groupId()).isEqualTo("938269591");
            assertThat(first.groupName()).isEqualTo("目标群");
            assertThat(first.inviterId()).isEqualTo("123456");
            assertThat(first.inviterName()).isEqualTo("邀请人");
            assertThat(first.message()).isEqualTo("来吧");
            assertThat(first.pending()).isTrue();
            assertThat(requests.get(1).sequence()).isEqualTo(1792837085640003L);
            assertThat(requests.get(1).kind()).isEqualTo(GroupSystemRequest.Kind.JOIN);
            assertThat(requests.get(1).eventType()).isEqualTo(GroupSystemRequest.EVENT_TYPE_JOIN);
            assertThat(requests.get(1).requesterName()).isEqualTo("希望永恒");
            assertThat(requests.get(1).pending()).isTrue();
            assertThat(requests.get(2).pending()).isFalse();
        }
    }

    @Test
    void listGroupRequestsHonoursLimitAndSortsBySequenceDescending() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            server.systemMsgBody = "{\"join_requests\":["
                    + "{\"request_id\":100,\"group_id\":1,\"checked\":false},"
                    + "{\"request_id\":300,\"group_id\":3,\"checked\":false},"
                    + "{\"request_id\":200,\"group_id\":2,\"checked\":false}]}";

            List<GroupSystemRequest> requests = adapter.listGroupRequests(2)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(requests).extracting(GroupSystemRequest::sequence)
                    .containsExactly(300L, 200L);
        }
    }

    @Test
    void decideGroupRequestSendsDecimalFlagAndApprove() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            SendReceipt receipt = adapter.decideGroupRequest(1792837085640003L, true, "欢迎")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(receipt.adapterMessageIds())
                    .containsExactly("group-request-1792837085640003");
            assertThat(server.paths).containsExactly("/set_group_add_request");
            JsonNode payload = server.payloads.getFirst();
            assertThat(payload.path("flag").asText()).isEqualTo("1792837085640003");
            assertThat(payload.path("approve").asBoolean()).isTrue();
            assertThat(payload.path("reason").asText()).isEqualTo("欢迎");
        }
    }

    @Test
    void decideGroupRequestReportsUnknownWithoutRetryWhenServerFails() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            server.addRequestStatus = 500;

            SendReceipt receipt = adapter.decideGroupRequest(42L, true, null)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.UNKNOWN);
            assertThat(receipt.errorCode()).isEqualTo("GROUP_REQUEST_UNKNOWN");
            assertThat(server.paths).containsExactly("/set_group_add_request");
        }
    }

    @Test
    void decideGroupRequestRejectsNonPositiveSequence() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            SendReceipt receipt = adapter.decideGroupRequest(0L, true, null)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(receipt.errorCode()).isEqualTo("INVALID_SEQUENCE");
            assertThat(server.paths).isEmpty();
        }
    }

    @Test
    void acceptGroupInvitationUsesSetGroupAddRequestFirst() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            SendReceipt receipt = adapter.acceptGroupInvitation("938269591", 1793612836122334L,
                            GroupSystemRequest.EVENT_TYPE_INVITE)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(receipt.adapterMessageIds())
                    .containsExactly("group-invite-1793612836122334");
            assertThat(server.paths).containsExactly("/set_group_add_request");
            JsonNode payload = server.payloads.getFirst();
            assertThat(payload.path("flag").asText()).isEqualTo("1793612836122334");
            assertThat(payload.path("approve").asBoolean()).isTrue();
        }
    }

    @Test
    void acceptGroupInvitationFallsBackToKernelWhenFlagIsUnknown() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            // 邀请卡片没有落成待处理通知时，set_group_add_request 会报 flag 不存在。
            server.addRequestRetcode = 1200;
            server.addRequestMessage = "flag 不存在";
            server.kernelErrorCode = 0;

            SendReceipt receipt = adapter.acceptGroupInvitation("938269591", 1793612836122334L,
                            GroupSystemRequest.EVENT_TYPE_INVITE)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(server.paths)
                    .containsExactly("/set_group_add_request", "/llonebot_debug");
            JsonNode payload = server.payloads.get(1);
            assertThat(payload.path("apiClass").asText()).isEqualTo("qqProtocol");
            assertThat(payload.path("method").asText()).isEqualTo("handleGroupRequest");
            JsonNode args = payload.path("args");
            assertThat(args.get(0).asLong()).isEqualTo(1793612836122334L);
            assertThat(args.get(1).asInt()).isEqualTo(GroupSystemRequest.EVENT_TYPE_INVITE);
            assertThat(args.get(2).asLong()).isEqualTo(938269591L);
            assertThat(args.get(3).asInt()).isEqualTo(1);
            assertThat(args.get(4).asText()).isEmpty();
            assertThat(args.get(5).asBoolean()).isFalse();
        }
    }

    @Test
    void acceptGroupInvitationSurfacesKernelRejection() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            server.addRequestRetcode = 1200;
            server.addRequestMessage = "flag 不存在";
            server.kernelErrorCode = 49;
            server.kernelErrorMsg = "invite expired";

            SendReceipt receipt = adapter.acceptGroupInvitation("938269591", 1793612836122334L,
                            GroupSystemRequest.EVENT_TYPE_INVITE)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(receipt.errorCode()).isEqualTo("GROUP_INVITE_REJECTED");
            assertThat(receipt.detail()).isEqualTo("invite expired");
        }
    }

    @Test
    void acceptGroupInvitationReportsUnknownWhenTransportFails() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            server.addRequestStatus = 502;

            SendReceipt receipt = adapter.acceptGroupInvitation("938269591", 1793612836122334L,
                            GroupSystemRequest.EVENT_TYPE_INVITE)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.UNKNOWN);
            assertThat(receipt.errorCode()).isEqualTo("GROUP_INVITE_UNKNOWN");
            assertThat(server.paths).containsExactly("/set_group_add_request");
        }
    }

    @Test
    void acceptGroupInvitationRejectsNonNumericGroupIdInKernelFallback() throws Exception {
        try (MockServer server = new MockServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            server.addRequestRetcode = 1200;
            server.addRequestMessage = "flag 不存在";

            SendReceipt receipt = adapter.acceptGroupInvitation("not-a-group", 7L,
                            GroupSystemRequest.EVENT_TYPE_INVITE)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(receipt.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(receipt.errorCode()).isEqualTo("INVALID_GROUP_ID");
            assertThat(server.paths).containsExactly("/set_group_add_request");
        }
    }

    private static NapCatConfig config(MockServer server) {
        return NapCatConfig.builder(
                        URI.create("ws://127.0.0.1:1/"),
                        server.baseUri())
                .accessToken("secret-token")
                .accountId("10001")
                .connectTimeout(Duration.ofMillis(200))
                .requestTimeout(Duration.ofSeconds(2))
                .maxTextLength(2000)
                .sendActionMode(NapCatConfig.SendActionMode.SEND_MSG)
                .initialReconnectDelay(Duration.ofMillis(50))
                .maxReconnectDelay(Duration.ofMillis(100))
                .reconnectJitter(0.0)
                .dedupeTtl(Duration.ofMinutes(1))
                .rateLimitPerSecond(1_000)
                .rateLimitBurst(1_000)
                .build();
    }

    private static final class MockServer implements AutoCloseable {

        private final HttpServer server;
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<JsonNode> payloads = new CopyOnWriteArrayList<>();
        private volatile String systemMsgBody = "{}";
        private volatile int systemMsgStatus = 200;
        private volatile int addRequestStatus = 200;
        private volatile int addRequestRetcode;
        private volatile String addRequestMessage = "";
        private volatile int kernelErrorCode;
        private volatile String kernelErrorMsg = "";

        private MockServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/get_group_system_msg", this::handleSystemMsg);
            server.createContext("/set_group_add_request", this::handleAddRequest);
            server.createContext("/llonebot_debug", this::handleKernel);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mock-napcat-group");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private void handleSystemMsg(HttpExchange exchange) throws IOException {
            record(exchange);
            if (systemMsgStatus != 200) {
                respond(exchange, systemMsgStatus, "{\"status\":\"failed\"}");
                return;
            }
            respond(exchange, 200,
                    "{\"status\":\"ok\",\"retcode\":0,\"data\":" + systemMsgBody + "}");
        }

        private void handleAddRequest(HttpExchange exchange) throws IOException {
            record(exchange);
            if (addRequestStatus != 200) {
                respond(exchange, addRequestStatus, "{\"status\":\"failed\"}");
                return;
            }
            respond(exchange, 200, "{\"status\":\"ok\",\"retcode\":" + addRequestRetcode
                    + ",\"message\":\"" + addRequestMessage + "\",\"data\":null}");
        }

        private void handleKernel(HttpExchange exchange) throws IOException {
            record(exchange);
            respond(exchange, 200, "{\"status\":\"ok\",\"retcode\":0,\"data\":{"
                    + "\"errorCode\":" + kernelErrorCode
                    + ",\"errorMsg\":\"" + kernelErrorMsg + "\"}}");
        }

        private void record(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = exchange.getRequestBody().readAllBytes();
            payloads.add(MAPPER.readTree(body));
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
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
