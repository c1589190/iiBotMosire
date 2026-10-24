package io.mosire.iibot.connect.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 平台级 QQ capability 的 HTTP 协议、目录解析和临时私聊发送测试。 */
class NapCatAdapterPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsRecentGroupsFriendsMembersAndResolvesMessage() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            List<ChatConversation> recent = adapter.recentConversations(10)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(recent).hasSize(2);
            assertThat(recent.get(0).sessionType()).isEqualTo(SessionType.GROUP);
            assertThat(recent.get(0).targetId()).isEqualTo("30003");
            assertThat(recent.get(0).displayName()).isEqualTo("测试群");
            assertThat(recent.get(1).sessionType()).isEqualTo(SessionType.PRIVATE);
            assertThat(recent.get(1).targetId()).isEqualTo("20002");
            assertThat(recent.get(1).lastMessageId()).isEqualTo("901");

            List<ChatConversation> groups = adapter.joinedGroups()
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(groups).singleElement().satisfies(group -> {
                assertThat(group.targetId()).isEqualTo("30003");
                assertThat(group.remark()).isEqualTo("群备注");
            });

            List<ChatConversation> friends = adapter.friends(true)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(friends).singleElement().satisfies(friend -> {
                assertThat(friend.targetId()).isEqualTo("20002");
                assertThat(friend.remark()).isEqualTo("好友备注");
            });
            assertThat(server.lastPayload("/get_friend_list").path("no_cache").asBoolean())
                    .isTrue();

            List<GroupMember> members = adapter.groupMembers("30003")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(members).singleElement().satisfies(member -> {
                assertThat(member.qqId()).isEqualTo("20002");
                assertThat(member.card()).isEqualTo("群名片");
                assertThat(member.role()).isEqualTo("member");
            });

            ChatMessageDetail message = adapter.message("777")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(message.sessionType()).isEqualTo(SessionType.GROUP);
            assertThat(message.targetId()).isEqualTo("30003");
            assertThat(message.senderId()).isEqualTo("20002");
            assertThat(message.text()).isEqualTo("我要报名");
            assertThat(server.paths).contains(
                    "/get_recent_contact", "/get_group_list", "/get_friend_list",
                    "/get_group_member_list", "/get_msg");
        }
    }

    @Test
    void sendsPrivateTemporaryAndGroupThroughTypedCapability() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            SendReceipt privateReceipt = adapter.sendPrivate(
                            "20002", "30003", List.of("报名表"), "idem-platform-private")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            SendReceipt groupReceipt = adapter.sendGroup(
                            "30003", List.of("群公告"), "idem-platform-group")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(privateReceipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(groupReceipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(server.sentPaths).containsExactly(
                    "/send_private_msg", "/send_group_msg");
            JsonNode privatePayload = server.sendPayloads.get(0);
            assertThat(privatePayload.path("user_id").asText()).isEqualTo("20002");
            assertThat(privatePayload.path("group_id").asText()).isEqualTo("30003");
            assertThat(privatePayload.path("message").get(0).path("data").path("text").asText())
                    .isEqualTo("报名表");
        }
    }

    @Test
    void historyCountsSkippedNonTextSegments() throws Exception {
        try (MockHttpServer server = new MockHttpServer();
             NapCatAdapter adapter = new NapCatAdapter(config(server))) {
            ChatHistoryPage page = adapter.history(new ChatHistoryQuery(
                            SessionType.PRIVATE, "20002", null, 2, true))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(page.messages()).hasSize(1);
            assertThat(page.messages().getFirst().text()).isEqualTo("历史文本");
            assertThat(page.nonTextSkipped()).isEqualTo(1);
        }
    }

    private static NapCatConfig config(MockHttpServer server) {
        return NapCatConfig.builder(URI.create("ws://127.0.0.1:1/"), server.baseUri())
                .accessToken("secret-token")
                .accountId("10001")
                .connectTimeout(Duration.ofMillis(200))
                .requestTimeout(Duration.ofSeconds(2))
                .maxTextLength(2_000)
                .sendActionMode(NapCatConfig.SendActionMode.DEDICATED)
                .initialReconnectDelay(Duration.ofMillis(50))
                .maxReconnectDelay(Duration.ofMillis(100))
                .reconnectJitter(0.0)
                .dedupeTtl(Duration.ofMinutes(1))
                .rateLimitPerSecond(1_000)
                .rateLimitBurst(1_000)
                .build();
    }

    private static final class MockHttpServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> sentPaths = new CopyOnWriteArrayList<>();
        private final List<JsonNode> sendPayloads = new CopyOnWriteArrayList<>();
        private final java.util.Map<String, JsonNode> payloads =
                new java.util.concurrent.ConcurrentHashMap<>();

        private MockHttpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/get_recent_contact", this::recent);
            server.createContext("/get_group_list", this::groups);
            server.createContext("/get_friend_list", this::friends);
            server.createContext("/get_group_member_list", this::members);
            server.createContext("/get_msg", this::message);
            server.createContext("/get_friend_msg_history", this::history);
            server.createContext("/send_private_msg", exchange ->
                    send(exchange, "/send_private_msg", "1"));
            server.createContext("/send_group_msg", exchange ->
                    send(exchange, "/send_group_msg", "2"));
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mock-napcat-platform");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private JsonNode lastPayload(String path) {
            return payloads.get(path);
        }

        private void recent(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            payloads.put(exchange.getRequestURI().getPath(), read(exchange));
            respond(exchange, """
                    {"status":"ok","retcode":0,"data":[
                      {"peerUin":"30003","peerName":"测试群","remark":"","msgTime":"1700000000","msgId":"900","chatType":2,
                       "lastestMsg":{"message_type":"group","group_id":30003}},
                      {"peerUin":"20002","peerName":"临时玩家","remark":"","msgTime":"1700000001","msgId":"901","chatType":100,
                       "lastestMsg":{"message_type":"private","group_id":30003}}
                    ]}
                    """);
        }

        private void groups(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            payloads.put(exchange.getRequestURI().getPath(), read(exchange));
            respond(exchange, """
                    {"status":"ok","retcode":0,"data":[
                      {"group_id":30003,"group_name":"测试群","group_remark":"群备注"}
                    ]}
                    """);
        }

        private void friends(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            payloads.put(exchange.getRequestURI().getPath(), read(exchange));
            respond(exchange, """
                    {"status":"ok","retcode":0,"data":[
                      {"user_id":20002,"nickname":"好友","remark":"好友备注"}
                    ]}
                    """);
        }

        private void members(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            payloads.put(exchange.getRequestURI().getPath(), read(exchange));
            respond(exchange, """
                    {"status":"ok","retcode":0,"data":[
                      {"user_id":20002,"nickname":"成员","card":"群名片","role":"member"}
                    ]}
                    """);
        }

        private void message(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            payloads.put(exchange.getRequestURI().getPath(), read(exchange));
            respond(exchange, """
                    {"status":"ok","retcode":0,"data":{
                      "message_id":"777","group_id":30003,"user_id":20002,"time":1700000000,
                      "sender":{"user_id":20002,"nickname":"成员","card":"群名片"},
                      "message":[{"type":"text","data":{"text":"我要报名"}}]
                    }}
                    """);
        }

        private void history(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            read(exchange);
            respond(exchange, """
                    {"status":"ok","retcode":0,"data":{"messages":[
                      {"message_id":801,"time":1700000000,"sender":{"user_id":20002,"nickname":"成员"},
                       "message":[{"type":"text","data":{"text":"历史文本"}}]},
                      {"message_id":802,"time":1700000001,"sender":{"user_id":20002,"nickname":"成员"},
                       "message":[{"type":"image","data":{}}]}
                    ]}}
                    """);
        }

        private void send(HttpExchange exchange, String path, String messageId)
                throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            sentPaths.add(path);
            sendPayloads.add(read(exchange));
            respond(exchange, "{\"status\":\"ok\",\"retcode\":0,\"data\":{\"message_id\":"
                    + messageId + "}}");
        }

        private static JsonNode read(HttpExchange exchange) throws IOException {
            return MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
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
