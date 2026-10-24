package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.mcp.ControlBindSecretaryTool;
import io.mosire.iibot.app.mcp.ControlInitializeTool;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.ControlRegisterPlayerTool;
import io.mosire.iibot.app.mcp.GmPostDecideTool;
import io.mosire.iibot.app.mcp.GmPostPublishTool;
import io.mosire.iibot.app.mcp.GmPublicationMcpTools;
import io.mosire.iibot.app.mcp.GmRecordMcpTools;
import io.mosire.iibot.app.publication.PublicationKind;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** P8-A 零玩家启动：66 工具、独立公共开场、零外发、动态控制面与重启恢复。 */
@Timeout(120)
class AppBootstrapP8OpeningTest {

    private static final String CONTROL_TOKEN = "p8-opening-token-6f1a2c";
    private static final String CONTROL_PATH = "/p8/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void zeroPlayerRuntimeCreatesPendingPublicOpeningPublishesOnlyPublicGroupAndAllowsDynamicControl()
            throws Exception {
        AppConfig config = config(tempDir.resolve("data"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer server = controlServer(runtime);
            PostService postService = field(server, "postService", PostService.class);
            PublicationOrchestrator publications = field(server,
                    "publicationOrchestrator", PublicationOrchestrator.class);
            ActionPackageService packageService = field(server,
                    "actionPackageService", ActionPackageService.class);
            EventContentStore contentStore = field(runtime, "eventContentStore",
                    EventContentStore.class);
            assertThat(server.boundPort()).isPositive();
            assertThat(runtime.engine().status().playerCount()).isZero();
            assertThat(runtime.engine().status().directorCount()).isEqualTo(1);

            client = client(server);
            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(71)
                    .contains(GmPublicationMcpTools.OPENING)
                    .containsAll(GmPublicationMcpTools.NAMES)
                    .containsAll(GmRecordMcpTools.NAMES);
            assertThat(adapter.sent()).isEmpty();

            McpSchema.CallToolResult initialized = client.callTool(
                    new McpSchema.CallToolRequest(ControlInitializeTool.NAME, Map.of()));
            assertThat(initialized.isError()).as(wireText(initialized)).isFalse();
            assertThat(data(initialized).path("playerCount").asInt()).isZero();
            assertThat(data(initialized).path("playerIds").size()).isZero();
            assertThat(adapter.sent()).isEmpty();

            String title = "雾灯港·序章-5b2e";
            String body = "潮水退去后，北码头只剩一盏雾灯-1d7c";
            String key = "p8-opening-primary";
            McpSchema.CallToolResult created = opening(client, title, body, key);
            assertThat(created.isError()).as(wireText(created)).isFalse();
            JsonNode createdData = data(created);
            String publicationId = createdData.path("publicationId").asText();
            String publicPostId = createdData.path("publicPostId").asText();
            assertThat(publicationId).isNotBlank();
            assertThat(publicPostId).isNotBlank();
            assertThat(createdData.path("kind").asText()).isEqualTo("OPENING");
            assertThat(wireText(created)).doesNotContain(body,
                    config.dataDir().toString());

            PublicationRecord record = publications.record(publicationId).orElseThrow();
            assertThat(record.kind()).isEqualTo(PublicationKind.OPENING);
            assertThat(record.batchId()).isNull();
            assertThat(record.turnId()).isNull();
            assertThat(record.privatePostIds()).isEmpty();
            assertThat(record.privateOutcomes()).isEmpty();
            Post post = postService.post(publicPostId).orElseThrow();
            assertThat(post.visibility()).isEqualTo(PostVisibility.PUBLIC);
            assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
            assertThat(post.authorPlayerId())
                    .isEqualTo(PostService.SYSTEM_PUBLICATION_AUTHOR);
            assertThat(post.body()).isEqualTo(body);
            assertThat(post.deliveryState()).isNull();
            assertThat(packageService.batches()).isEmpty();
            assertThat(runtime.engine().turnView()).isEmpty();
            assertThat(runtime.engine().status().playerCount()).isZero();

            List<StoredEvent> events = contentStore.readByTypePrefix(
                    DomainEventTypes.PUBLICATION_PREFIX);
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.payloadJson())
                        .contains(publicationId, "OPENING", "contentHash")
                        .doesNotContain(body, title);
                assertThat(event.content().json())
                        .contains("\"kind\":\"OPENING\"", body);
            });

            McpSchema.CallToolResult duplicate = opening(client,
                    "不应覆盖标题", "不应覆盖正文-2f30", key);
            assertThat(duplicate.isError()).as(wireText(duplicate)).isFalse();
            assertThat(data(duplicate).path("publicationId").asText())
                    .isEqualTo(publicationId);
            assertThat(data(duplicate).path("publicPostId").asText())
                    .isEqualTo(publicPostId);
            assertThat(postService.postsForGm()).hasSize(1);
            assertThat(postService.post(publicPostId).orElseThrow().body()).isEqualTo(body);
            assertThat(contentStore.readByTypePrefix(
                    DomainEventTypes.PUBLICATION_PREFIX)).hasSize(1);
            assertThat(adapter.sent()).isEmpty();

            McpSchema.CallToolResult approved = client.callTool(
                    new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                            "postId", publicPostId,
                            "decision", "APPROVE",
                            "expectedRevision", 1L,
                            "idempotencyKey", "p8-opening-approve",
                            "reason", "P8-A 开场审核")));
            assertThat(approved.isError()).as(wireText(approved)).isFalse();
            assertThat(adapter.sent()).isEmpty();

            McpSchema.CallToolResult published = client.callTool(
                    new McpSchema.CallToolRequest(GmPostPublishTool.NAME, Map.of(
                            "postId", publicPostId,
                            "expectedRevision", 2L,
                            "idempotencyKey", "p8-opening-publish")));
            assertThat(published.isError()).as(wireText(published)).isFalse();
            await(() -> postService.post(publicPostId).orElseThrow().deliveryState()
                            == DeliveryState.SENT,
                    "开场推文发布后应完成公共群发送");
            assertThat(adapter.sent()).singleElement().satisfies(message -> {
                assertThat(message.targetType())
                        .isEqualTo(OutboundMessage.TargetType.GROUP);
                assertThat(message.targetId()).isEqualTo("group-public");
                assertThat(message.textSegments()).containsExactly(body);
            });

            Path openingDir = config.dataDir().resolve("records/openings")
                    .resolve(publicationId);
            await(() -> Files.isRegularFile(openingDir.resolve("index.md"))
                            && Files.isRegularFile(
                            openingDir.resolve("public-narrative.md"))
                            && Files.isRegularFile(openingDir.resolve(
                            "posts/" + publicPostId + "-PUBLIC.md")),
                    "开场推文应投影到 records/openings");
            McpSchema.CallToolResult recordQuery = client.callTool(
                    new McpSchema.CallToolRequest(GmRecordMcpTools.QUERY,
                            Map.of("kind", "opening")));
            assertThat(recordQuery.isError()).as(wireText(recordQuery)).isFalse();
            assertThat(wireText(recordQuery)).contains(publicationId, publicPostId)
                    .doesNotContain(body, config.dataDir().toString());
            JsonNode openingRow = data(recordQuery).path("records").get(0);
            assertThat(openingRow.path("contentHash").asText()).hasSize(64);
            assertThat(openingRow.path("turnId").isNull()).isTrue();

            long engineRevision = runtime.engine().status().engineRevision();
            McpSchema.CallToolResult registered = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME,
                            Map.of("playerId", "player-3",
                                    "qqId", "qq-p8",
                                    "displayName", "P8 动态玩家",
                                    "idempotencyKey", "p8-register-player")));
            assertThat(registered.isError()).as(wireText(registered)).isFalse();
            assertThat(runtime.engine().status().playerCount()).isEqualTo(1);
            assertThat(runtime.controlBootstrapService().resolve("qq-p8")).isPresent();
            assertThat(engineRevision).isLessThan(
                    runtime.engine().status().engineRevision());

            McpSchema.CallToolResult bound = client.callTool(
                    new McpSchema.CallToolRequest(ControlBindSecretaryTool.NAME,
                            Map.of("playerId", "player-3",
                                    "idempotencyKey", "p8-bind-secretary")));
            assertThat(bound.isError()).as(wireText(bound)).isFalse();
            assertThat(data(bound).path("secretaryBound").asBoolean()).isTrue();

            McpSchema.CallToolResult opened = client.callTool(
                    new McpSchema.CallToolRequest("tf_open_turn", Map.of(
                            "expectedRevision",
                            runtime.engine().status().engineRevision(),
                            "idempotencyKey", "p8-open-turn",
                            "reason", "P8-A 动态玩家开回合",
                            "minutes", 30)));
            assertThat(opened.isError()).as(wireText(opened)).isFalse();
            assertThat(runtime.engine().turnView()).isPresent();
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.OPEN);

            McpSchema.CallToolResult afterDynamic = client.callTool(
                    new McpSchema.CallToolRequest(ControlInitializeTool.NAME, Map.of()));
            assertThat(afterDynamic.isError()).as(wireText(afterDynamic)).isFalse();
            assertThat(data(afterDynamic).path("playerCount").asInt()).isEqualTo(1);
            assertThat(data(afterDynamic).path("secretaryCount").asInt())
                    .isGreaterThanOrEqualTo(1);
            assertThat(adapter.sent()).hasSize(1);
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void openingPostAndDynamicPlayerRecoverAfterRestartWithoutDuplicatePostEventOrSend()
            throws Exception {
        AppConfig config = config(tempDir.resolve("restart-data"));
        String title = "重启开场标题-8a10";
        String body = "重启开场正文-只应保留一份-4c62";
        String key = "p8-opening-restart";
        String publicationId;
        String publicPostId;
        FakeMessagingAdapter firstAdapter = new FakeMessagingAdapter();
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), firstAdapter));
        McpSyncClient firstClient = null;
        try {
            first.start();
            firstClient = client(controlServer(first));
            McpSchema.CallToolResult created = opening(firstClient, title, body, key);
            assertThat(created.isError()).as(wireText(created)).isFalse();
            publicationId = data(created).path("publicationId").asText();
            publicPostId = data(created).path("publicPostId").asText();
            McpSchema.CallToolResult approved = firstClient.callTool(
                    new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                            "postId", publicPostId,
                            "decision", "APPROVE",
                            "expectedRevision", 1L,
                            "idempotencyKey", "p8-restart-approve",
                            "reason", "P8-A 重启前审核")));
            assertThat(approved.isError()).as(wireText(approved)).isFalse();
            McpSchema.CallToolResult published = firstClient.callTool(
                    new McpSchema.CallToolRequest(GmPostPublishTool.NAME, Map.of(
                            "postId", publicPostId,
                            "expectedRevision", 2L,
                            "idempotencyKey", "p8-restart-publish")));
            assertThat(published.isError()).as(wireText(published)).isFalse();
            await(() -> firstAdapter.sent().size() == 1,
                    "重启前开场推文应已发送公共群");
            McpSchema.CallToolResult registered = firstClient.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME,
                            Map.of("playerId", "player-3",
                                    "qqId", "qq-p8",
                                    "displayName", "P8 重启玩家",
                                    "idempotencyKey", "p8-restart-register")));
            assertThat(registered.isError()).as(wireText(registered)).isFalse();
            McpSchema.CallToolResult bound = firstClient.callTool(
                    new McpSchema.CallToolRequest(ControlBindSecretaryTool.NAME,
                            Map.of("playerId", "player-3",
                                    "idempotencyKey", "p8-restart-bind")));
            assertThat(bound.isError()).as(wireText(bound)).isFalse();
        } finally {
            if (firstClient != null) {
                firstClient.closeGracefully();
            }
            first.close();
        }

        FakeMessagingAdapter secondAdapter = new FakeMessagingAdapter();
        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), secondAdapter));
        McpSyncClient secondClient = null;
        try {
            second.start();
            ControlMcpServer server = controlServer(second);
            PostService postService = field(server, "postService", PostService.class);
            PublicationOrchestrator publications = field(server,
                    "publicationOrchestrator", PublicationOrchestrator.class);
            EventContentStore contentStore = field(second, "eventContentStore",
                    EventContentStore.class);
            secondClient = client(server);

            assertThat(second.engine().status().playerCount()).isEqualTo(1);
            assertThat(second.engine().playerProfiles())
                    .extracting(profile -> profile.playerId())
                    .contains("player-3");
            assertThat(second.controlBootstrapService().resolve("qq-p8")).isPresent();
            assertThat(config.dataDir().resolve("secretaries/player-3"))
                    .isDirectory();

            PublicationRecord restored = publications.record(publicationId)
                    .orElseThrow();
            assertThat(restored.kind()).isEqualTo(PublicationKind.OPENING);
            assertThat(restored.publicPostId()).isEqualTo(publicPostId);
            Post restoredPost = postService.post(publicPostId).orElseThrow();
            assertThat(restoredPost.status()).isEqualTo(PostStatus.PUBLISHED);
            assertThat(restoredPost.deliveryState()).isEqualTo(DeliveryState.SENT);
            assertThat(restoredPost.body()).isEqualTo(body);
            assertThat(postService.postsForGm()).hasSize(1);
            assertThat(contentStore.readByTypePrefix(
                    DomainEventTypes.PUBLICATION_PREFIX)).hasSize(1);
            assertThat(secondAdapter.sent()).isEmpty();

            McpSchema.CallToolResult initialized = secondClient.callTool(
                    new McpSchema.CallToolRequest(ControlInitializeTool.NAME, Map.of()));
            assertThat(initialized.isError()).as(wireText(initialized)).isFalse();
            assertThat(data(initialized).path("playerCount").asInt()).isEqualTo(1);
            assertThat(data(initialized).path("secretaryCount").asInt())
                    .isGreaterThanOrEqualTo(1);

            McpSchema.CallToolResult duplicate = opening(secondClient,
                    "重启后不同标题", "重启后不同正文-不得新增", key);
            assertThat(duplicate.isError()).as(wireText(duplicate)).isFalse();
            assertThat(data(duplicate).path("publicationId").asText())
                    .isEqualTo(publicationId);
            assertThat(data(duplicate).path("publicPostId").asText())
                    .isEqualTo(publicPostId);
            assertThat(postService.postsForGm()).hasSize(1);
            assertThat(contentStore.readByTypePrefix(
                    DomainEventTypes.PUBLICATION_PREFIX)).hasSize(1);
            assertThat(postService.post(publicPostId).orElseThrow().body()).isEqualTo(body);
            assertThat(secondAdapter.sent()).isEmpty();

            McpSchema.CallToolResult recordQuery = secondClient.callTool(
                    new McpSchema.CallToolRequest(GmRecordMcpTools.QUERY,
                            Map.of("kind", "opening")));
            assertThat(recordQuery.isError()).as(wireText(recordQuery)).isFalse();
            assertThat(wireText(recordQuery)).contains(publicationId, publicPostId)
                    .doesNotContain(body, config.dataDir().toString());
        } finally {
            if (secondClient != null) {
                secondClient.closeGracefully();
            }
            second.close();
        }
    }

    private AppConfig config(Path dataDir) throws Exception {
        Path factsFile = TestSupport.writeFacts(
                tempDir.resolve("world").resolve("facts.json"), """
                        {"facts": [
                          {"factId": "public-1", "content": "港口迷雾与失踪船队",
                           "visibility": "PUBLIC"}
                        ]}
                        """);
        ObjectNode root = TestSupport.validConfigNode(dataDir, factsFile);
        ((ArrayNode) root.get("players")).removeAll();
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        return TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot-p8.json"), root));
    }

    private static SecretProvider secrets() {
        return SecretProvider.of(Map.of(
                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN,
                "IIBOT_LLM_KEY", "llm-key"));
    }

    private static McpSchema.CallToolResult opening(McpSyncClient client,
                                                    String title, String body,
                                                    String key) {
        return client.callTool(new McpSchema.CallToolRequest(
                GmPublicationMcpTools.OPENING, Map.of(
                        "title", title,
                        "body", body,
                        "idempotencyKey", key)));
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        return field(runtime, "controlPlane", ControlMcpServer.class);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private McpSyncClient client(ControlMcpServer server) {
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

    private static JsonNode data(McpSchema.CallToolResult result) throws Exception {
        return JSON.readTree(wireText(result)).path("data");
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(description + "（等待被中断）", interrupted);
            }
        }
        throw new AssertionError(description);
    }
}
