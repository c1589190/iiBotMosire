package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.inbox.InboxBindingDescriptorTool;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真 socket MCP 验收：Mode A 工具面、push/claim/complete/release、宿主通知桥与关闭语义。 */
@Timeout(90)
class AppBootstrapInboxMcpTest {

    private static final String CONTROL_TOKEN = "inbox-control-token-4f7f6b";
    private static final String CONTROL_PATH = "/inbox/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INBOX_TOOL_NAMES = List.of(
            "inbox_status", "inbox_rest", "inbox_list", "inbox_view", "inbox_update",
            "inbox_append", "inbox_delete", "inbox_claim", "inbox_renew",
            "inbox_complete", "inbox_release", "inbox_push", "inbox_occupancy",
            "inbox_occupy", "inbox_vacate", "inbox_credential_info",
            InboxBindingDescriptorTool.NAME);

    @TempDir
    Path tempDir;

    @Test
    void modeAMcpSurfaceBridgeLifecycleAndTokenBoundary() throws Exception {
        Path facts = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"}
                ]}
                """);
        Path inboxDir = tempDir.resolve("inbox-state").toAbsolutePath().normalize();
        ObjectNode root = TestSupport.validConfigNode(tempDir.resolve("data"), facts);
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        ObjectNode inbox = root.putObject("inbox");
        inbox.put("enabled", true);
        inbox.put("dataDir", inboxDir.toString());
        inbox.put("inboxId", "app-a");
        inbox.put("bindingLabel", "dsh-session-S1");
        inbox.putArray("channels").add("ticket").add("publication-review");
        inbox.put("pollIntervalMillis", 100);
        inbox.put("bindingTtlHours", 1);
        AppConfig config = TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot-inbox.json"), root));

        AppRuntime runtime = null;
        McpSyncClient client = null;
        int inboxPort = -1;
        try {
            runtime = AppBootstrap.build(config, BootstrapOptions.fake(
                    SecretProvider.of(Map.of(
                            "IIBOT_NAPCAT_TOKEN", "napcat-token",
                            "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN)),
                    Clock.systemUTC(), FakeLlmClient.with(), new FakeMessagingAdapter()));
            runtime.start();
            ControlMcpServer controlServer = controlServer(runtime);
            client = client(controlServer);

            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).containsAll(INBOX_TOOL_NAMES);
            assertThat(names.stream().filter(name -> name.startsWith("inbox_")).count())
                    .as("Inbox 客户端工具面必须恰为 16 个")
                    .isEqualTo(16L);
            assertThat(names).doesNotContain("tf_ticket_board");
            assertThat(names).contains("tf_ticket_list", "tf_ticket_get");

            String programToken = Files.readString(inboxDir.resolve("program-mcp.token"));
            String wakeToken = Files.readString(inboxDir.resolve("dsh-wake.token"));
            assertThat(programToken).isNotBlank();
            assertThat(wakeToken).isNotBlank();

            JsonNode descriptorOutput = callJson(client, InboxBindingDescriptorTool.NAME, Map.of());
            List<String> outputFields = new ArrayList<>();
            descriptorOutput.fieldNames().forEachRemaining(outputFields::add);
            assertThat(outputFields).containsExactlyInAnyOrder("descriptor", "descriptorFile");
            JsonNode descriptor = descriptorOutput.path("descriptor");
            List<String> descriptorFields = new ArrayList<>();
            descriptor.fieldNames().forEachRemaining(descriptorFields::add);
            assertThat(descriptorFields).containsExactlyInAnyOrder(
                    "inboxId", "apiBaseUrl", "adapterTokenRef", "channels", "label");
            assertThat(descriptor.path("inboxId").asText()).isEqualTo("app-a");
            assertThat(descriptor.path("label").asText()).isEqualTo("dsh-session-S1");
            assertThat(descriptorOutput.toString())
                    .doesNotContain(programToken).doesNotContain(wakeToken);
            assertThat(descriptorOutput.path("descriptorFile").asText())
                    .isEqualTo(inboxDir.resolve("binding-descriptor.json").toString());
            inboxPort = URI.create(descriptor.path("apiBaseUrl").asText()).getPort();
            assertPortOpen(inboxPort);

            JsonNode credential = callJson(client, "inbox_credential_info", Map.of());
            assertThat(credential.path("label").asText()).isEqualTo("iibot-program-mcp");
            assertThat(credential.toString()).doesNotContain(programToken).doesNotContain(wakeToken);

            JsonNode occupied = callJson(client, "inbox_occupy", Map.of(
                    "occupiedBy", "mcp-tool-test"));
            assertThat(occupied.path("occupied").asBoolean()).isTrue();
            assertThat(occupied.path("occupiedBy").asText()).isEqualTo("mcp-tool-test");
            JsonNode occupancy = callJson(client, "inbox_occupancy", Map.of());
            assertThat(occupancy.path("occupied").asBoolean()).isTrue();
            JsonNode vacated = callJson(client, "inbox_vacate", Map.of());
            assertThat(vacated.path("occupied").asBoolean()).isFalse();

            JsonNode pushed = callJson(client, "inbox_push", Map.of(
                    "channel", "ticket",
                    "kind", "manual",
                    "summary", "manual notification",
                    "content", Map.of("ticketId", "manual-ticket-ref"),
                    "dedupeKey", "manual:mcp:1",
                    "priority", 0));
            String firstItemId = pushed.path("itemId").asText();
            assertThat(firstItemId).startsWith("itm_");

            JsonNode firstList = callJson(client, "inbox_list", Map.of("limit", 50));
            assertThat(firstList.path("items")).anySatisfy(item -> {
                assertThat(item.path("itemId").asText()).isEqualTo(firstItemId);
                assertThat(item.path("channel").asText()).isEqualTo("ticket");
                assertThat(item.path("summary").asText()).isEqualTo("manual notification");
                assertThat(item.has("content")).isFalse();
                assertThat(item.has("payload")).isFalse();
            });
            JsonNode claimed = callJson(client, "inbox_claim", Map.of(
                    "itemIds", List.of(firstItemId), "leaseSeconds", 60));
            JsonNode firstClaim = claimed.path("claimed").get(0);
            String firstClaimToken = firstClaim.path("claimToken").asText();
            assertThat(firstClaim.path("content").path("ticketId").asText())
                    .isEqualTo("manual-ticket-ref");
            assertThat(firstClaim.path("summary").asText())
                    .isEqualTo("manual notification");
            assertThat(claimed.toString()).doesNotContain(programToken).doesNotContain(wakeToken);
            JsonNode renewed = callJson(client, "inbox_renew", Map.of(
                    "claimTokens", List.of(firstClaimToken), "leaseSeconds", 120));
            assertThat(renewed.path("renewed")).hasSize(1);
            assertThat(renewed.path("renewed").get(0).path("itemId").asText())
                    .isEqualTo(firstItemId);

            JsonNode completed = callJson(client, "inbox_complete", Map.of(
                    "completions", List.of(Map.of(
                            "claimToken", firstClaimToken,
                            "outcome", "completed",
                            "summary", "mcp smoke complete"))));
            assertThat(completed.path("completed").get(0).path("outcome").asText())
                    .isEqualToIgnoringCase("completed");

            JsonNode secondPush = callJson(client, "inbox_push", Map.of(
                    "channel", "ticket",
                    "summary", "release notification",
                    "content", Map.of("ticketId", "manual-ticket-release"),
                    "dedupeKey", "manual:mcp:release"));
            String secondItemId = secondPush.path("itemId").asText();
            JsonNode secondClaim = callJson(client, "inbox_claim", Map.of(
                    "itemIds", List.of(secondItemId), "leaseSeconds", 60));
            String secondClaimToken = secondClaim.path("claimed").get(0)
                    .path("claimToken").asText();
            JsonNode released = callJson(client, "inbox_release", Map.of(
                    "claimTokens", List.of(secondClaimToken)));
            assertThat(released.path("releasedItemIds"))
                    .anySatisfy(id -> assertThat(id.asText()).isEqualTo(secondItemId));

            JsonNode afterReleaseList = callJson(client, "inbox_list", Map.of("limit", 50));
            assertThat(afterReleaseList.path("items")).anySatisfy(item ->
                    assertThat(item.path("itemId").asText()).isEqualTo(secondItemId));
            JsonNode reclaim = callJson(client, "inbox_claim", Map.of(
                    "itemIds", List.of(secondItemId), "leaseSeconds", 60));
            callJson(client, "inbox_complete", Map.of(
                    "completions", List.of(Map.of(
                            "claimToken", reclaim.path("claimed").get(0)
                                    .path("claimToken").asText(),
                            "outcome", "completed"))));

            TicketService tickets = ticketService(controlServer);
            PostService posts = postService(controlServer);
            var ticket = tickets.submit(new Actor("player-1", ActorRole.PLAYER),
                    TicketType.ACTION_DRAFT, "HIDDEN_TICKET_TITLE",
                    "HIDDEN_TICKET_BODY", "inbox-bridge-ticket-1");
            var post = posts.createDraft(new Actor("player-1", ActorRole.PLAYER),
                    "HIDDEN_POST_TITLE", "HIDDEN_POST_BODY", PostVisibility.PUBLIC,
                    0L, "inbox-bridge-post-1");
            assertThat(ticket.ok()).isTrue();
            assertThat(post.ok()).isTrue();

            JsonNode bridgedStatus = awaitAvailable(client, 2, Duration.ofSeconds(10));
            long bridgedRevision = bridgedStatus.path("revision").asLong();
            JsonNode bridgedList = callJson(client, "inbox_list", Map.of(
                    "channels", List.of("ticket", "publication-review"), "limit", 50));
            assertThat(bridgedList.path("count").asInt()).isEqualTo(2);
            assertThat(bridgedList.toString())
                    .contains("ACTION_DRAFT")
                    .contains("PUBLIC")
                    .doesNotContain("HIDDEN_TICKET_TITLE", "HIDDEN_TICKET_BODY",
                            "HIDDEN_POST_TITLE", "HIDDEN_POST_BODY",
                            programToken, wakeToken);

            Thread.sleep(400);
            JsonNode afterRepeatedPoll = callJson(client, "inbox_status", Map.of(
                    "channels", List.of("ticket", "publication-review")));
            assertThat(afterRepeatedPoll.path("available").asInt()).isEqualTo(2);
            assertThat(afterRepeatedPoll.path("revision").asLong()).isEqualTo(bridgedRevision);

            List<String> bridgedIds = new ArrayList<>();
            bridgedList.path("items").forEach(item ->
                    bridgedIds.add(item.path("itemId").asText()));
            JsonNode bridgedClaims = callJson(client, "inbox_claim", Map.of(
                    "itemIds", bridgedIds, "leaseSeconds", 60));
            assertThat(bridgedClaims.path("claimed")).hasSize(2);
            List<String> bridgedClaimTokens = new ArrayList<>();
            for (JsonNode item : bridgedClaims.path("claimed")) {
                String channel = item.path("channel").asText();
                JsonNode content = item.path("content");
                if ("ticket".equals(channel)) {
                    List<String> keys = new ArrayList<>();
                    content.fieldNames().forEachRemaining(keys::add);
                    assertThat(keys).containsExactlyInAnyOrder(
                            "ticketId", "type", "status", "revision");
                } else {
                    List<String> keys = new ArrayList<>();
                    content.fieldNames().forEachRemaining(keys::add);
                    assertThat(keys).containsExactlyInAnyOrder(
                            "postId", "visibility", "revision");
                }
                assertThat(content.toString()).doesNotContain("HIDDEN_TICKET_TITLE",
                        "HIDDEN_TICKET_BODY", "HIDDEN_POST_TITLE", "HIDDEN_POST_BODY",
                        "player-1", programToken, wakeToken);
                bridgedClaimTokens.add(item.path("claimToken").asText());
            }
            JsonNode bridgeCompleted = callJson(client, "inbox_complete", Map.of(
                    "completions", bridgedClaimTokens.stream()
                            .map(token -> Map.of("claimToken", token, "outcome", "completed"))
                            .toList()));
            assertThat(bridgeCompleted.path("completed")).hasSize(2);
            assertThat(bridgeCompleted.path("available").asInt()).isZero();
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            if (runtime != null) {
                runtime.close();
            }
        }

        int closedPort = inboxPort;
        assertThatThrownBy(() -> assertPortOpen(closedPort))
                .as("app close 后 embedded Inbox 端口必须关闭")
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.isDirectory(inboxDir)).isTrue();
        assertThat(Files.exists(inboxDir.resolve("inbox.db"))).isTrue();
    }

    private static JsonNode awaitAvailable(McpSyncClient client, long expected,
                                           Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = callJson(client, "inbox_status", Map.of(
                    "channels", List.of("ticket", "publication-review")));
            if (last.path("available").asLong() >= expected) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待 inbox available=" + expected + " 超时，最后状态=" + last);
    }

    private static JsonNode callJson(McpSyncClient client, String tool,
                                     Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, args));
        assertThat(result.isError()).as(tool + " -> " + wireText(result)).isFalse();
        return JSON.readTree(wireText(result));
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        return (ControlMcpServer) field.get(runtime);
    }

    private static TicketService ticketService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("ticketService");
        field.setAccessible(true);
        return (TicketService) field.get(server);
    }

    private static PostService postService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("postService");
        field.setAccessible(true);
        return (PostService) field.get(server);
    }

    private static McpSyncClient client(ControlMcpServer server) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.boundPort())
                .endpoint(CONTROL_PATH)
                .resumableStreams(false)
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + CONTROL_TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private static void assertPortOpen(int port) throws java.io.IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        }
    }
}
