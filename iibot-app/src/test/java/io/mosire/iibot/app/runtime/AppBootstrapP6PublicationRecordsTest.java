package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.GmPostDecideTool;
import io.mosire.iibot.app.mcp.GmPostPublishTool;
import io.mosire.iibot.app.mcp.GmPublicationMcpTools;
import io.mosire.iibot.app.mcp.GmRecordMcpTools;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.records.RecordProjectionException;
import io.mosire.iibot.app.records.TurnRecordProjector;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchExecutor;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageView;
import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6 组合根：65 工具、公开草稿不外发、成功 mutation 触发投影、投影失败隔离。 */
@Timeout(90)
class AppBootstrapP6PublicationRecordsTest {

    private static final String CONTROL_TOKEN = "p6-publication-records-token-4e8c1d";
    private static final String CONTROL_PATH = "/p6/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void publicationGenerateIsPendingOnlyProjectionRunsAndPublishStillDispatchesAfterProjectionFailure()
            throws Exception {
        AppConfig config = config();
        String publicBody = "组合根公共发布正文-31da";
        String privateBody = "组合根私密发布正文-8cb7";
        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.text(batchJson("组合根批次推演结果",
                        Map.of("player-1", "组合根私密行动结果"))),
                LlmResponse.text(publicationJson(publicBody,
                        Map.of("player-1", privateBody))));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(), llm, adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer server = controlServer(runtime);
            PostService postService = field(server, "postService", PostService.class);
            ActionPoolService actionPool = field(server, "actionPoolService",
                    ActionPoolService.class);
            ActionPackageService packageService = field(server, "actionPackageService",
                    ActionPackageService.class);
            ActionBatchExecutor batchExecutor = field(server, "actionBatchExecutor",
                    ActionBatchExecutor.class);
            PublicationOrchestrator publications = field(server, "publicationOrchestrator",
                    PublicationOrchestrator.class);
            Actor supervisor = new Actor(ControlBootstrapService.SUPERVISOR_ACTOR_ID,
                    ActorRole.SUPERVISOR);

            Actor director = new Actor("director-1", ActorRole.DIRECTOR);
            CommandResult opened = runtime.engine().openTurn(director,
                    runtime.engine().status().engineRevision(),
                    "p6-integration-open-turn", Instant.now().plus(Duration.ofHours(2)));
            assertThat(opened.ok()).isTrue();
            PlayerAction action = actionPool.submit(
                    new Actor("player-1", ActorRole.PLAYER), "组合根 P6 集成行动",
                    "chat", "p6-integration-action");
            PackageView pkg = packageService.create(supervisor, "组合根 P6 集成包",
                    List.of(action.actionId()), 3_600, "p6-integration-package");
            packageService.decide(supervisor, pkg.packageId(), Set.of(0),
                    "批准组合根 P6 集成包");
            CommitReceipt receipt = packageService.commit(supervisor, pkg.packageId(),
                    batchExecutor);
            assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
            ActionBatch batch = packageService.batches().stream()
                    .filter(candidate -> candidate.batchId().equals(receipt.revisionRef()))
                    .findFirst().orElseThrow();
            assertThat(batch.status().name()).isEqualTo("COMMITTED");
            assertThat(batch.turnId()).isEqualTo(runtime.engine().turnView().orElseThrow().turnId());

            client = client(server);
            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(71)
                    .containsAll(GmPublicationMcpTools.NAMES)
                    .contains(GmPublicationMcpTools.OPENING)
                    .containsAll(GmRecordMcpTools.NAMES)
                    .doesNotContain("tf_qq_send", "qq_send", "tf_confirm_decision", "shutdown");

            int sentBefore = adapter.sent().size();
            McpSchema.CallToolResult generated = client.callTool(
                    new McpSchema.CallToolRequest(GmPublicationMcpTools.GENERATE, Map.of(
                            "batchId", batch.batchId(),
                            "idempotencyKey", "p6-integration-generate")));
            assertThat(generated.isError()).as(wireText(generated)).isFalse();
            JsonNode generatedData = data(generated);
            String publicationId = generatedData.path("publicationId").asText();
            String publicPostId = generatedData.path("publicPostId").asText();
            String privatePostId = generatedData.path("privatePostIds")
                    .path("player-1").asText();
            assertThat(publicationId).isNotBlank();
            assertThat(publicPostId).isNotBlank();
            assertThat(privatePostId).isNotBlank();
            assertThat(postService.post(publicPostId).orElseThrow().status())
                    .isEqualTo(PostStatus.PENDING_APPROVAL);
            assertThat(postService.post(privatePostId).orElseThrow().status())
                    .isEqualTo(PostStatus.PENDING_APPROVAL);

            Path turnDir = runtime.config().dataDir().resolve("records/turns")
                    .resolve(runtime.engine().turnView().orElseThrow().turnNo() + "-"
                            + runtime.engine().turnView().orElseThrow().turnId());
            await(() -> Files.isRegularFile(
                            turnDir.resolve("posts/" + publicPostId + "-PUBLIC.md"))
                            && Files.isRegularFile(
                            turnDir.resolve("posts/" + privatePostId + "-PRIVATE.md")),
                    "成功 mutation 后 records 投影应由 afterMutation 触发");
            assertThat(turnDir.resolve("index.md")).isRegularFile();
            assertThat(turnDir.resolve("public-narrative.md")).isRegularFile();
            assertThat(turnDir.resolve("players/player-1.md")).isRegularFile();
            assertThat(turnDir.resolve("posts/" + publicPostId + "-PUBLIC.md")).isRegularFile();
            assertThat(turnDir.resolve("posts/" + privatePostId + "-PRIVATE.md")).isRegularFile();
            assertThat(Files.readString(turnDir.resolve("public-narrative.md")))
                    .contains(publicBody).doesNotContain(privateBody);
            assertThat(Files.readString(turnDir.resolve("players/player-1.md")))
                    .contains(privateBody).doesNotContain(publicBody);
            // afterMutation 已完整走完投影组件；待审核草稿仍不得触发任何 QQ/适配器发送。
            assertThat(adapter.sent()).hasSize(sentBefore);

            McpSchema.CallToolResult recordList = client.callTool(
                    new McpSchema.CallToolRequest(GmRecordMcpTools.TURN_LIST, Map.of()));
            assertThat(recordList.isError()).as(wireText(recordList)).isFalse();
            assertThat(wireText(recordList)).contains(batch.batchId(), publicationId)
                    .doesNotContain(publicBody, privateBody,
                            runtime.config().dataDir().toString());

            // 主动制造投影失败：MCP mutation 仍应成功；随后 publish 的既有分发仍应工作。
            Path turns = runtime.config().dataDir().resolve("records/turns");
            Path parkedTurns = runtime.config().dataDir().resolve("records/turns-parked");
            Files.move(turns, parkedTurns);
            Path outside = tempDir.resolve("p6-outside");
            Files.createDirectories(outside);
            Files.createSymbolicLink(turns, outside);
            TurnRecordProjector probe = new TurnRecordProjector(runtime.config().dataDir(),
                    runtime.engine(), packageService, postService, publications);
            assertThatThrownBy(probe::rebuildAll)
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));

            McpSchema.CallToolResult approved = client.callTool(
                    new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                            "postId", publicPostId,
                            "decision", "APPROVE",
                            "expectedRevision", 1L,
                            "idempotencyKey", "p6-integration-approve",
                            "reason", "P6 组合根审核")));
            assertThat(approved.isError()).as(wireText(approved)).isFalse();
            assertThat(postService.post(publicPostId).orElseThrow().status())
                    .isEqualTo(PostStatus.APPROVED);
            McpSchema.CallToolResult published = client.callTool(
                    new McpSchema.CallToolRequest(GmPostPublishTool.NAME, Map.of(
                            "postId", publicPostId,
                            "expectedRevision", 2L,
                            "idempotencyKey", "p6-integration-publish")));
            assertThat(published.isError())
                    .as("投影失败不得改写 MCP 返回: %s", wireText(published)).isFalse();
            assertThat(postService.post(publicPostId).orElseThrow().status())
                    .isEqualTo(PostStatus.PUBLISHED);
            await(() -> postService.post(publicPostId).orElseThrow().deliveryState()
                            == DeliveryState.SENT,
                    "PUBLISHED 后 afterMutation 的 PostDistributor 应完成公开群发送与送达标记");
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.GROUP);
                assertThat(message.targetId()).isEqualTo("group-public");
                assertThat(message.textSegments()).containsExactly(publicBody);
            });
            try (var files = Files.list(outside)) {
                assertThat(files).isEmpty();
            }

            // GET 类记录工具仍可返回此前成功投影摘要；坏投影不会回滚领域事件。
            McpSchema.CallToolResult query = client.callTool(
                    new McpSchema.CallToolRequest(GmRecordMcpTools.QUERY, Map.of(
                            "kind", "post", "postId", publicPostId)));
            assertThat(query.isError()).as(wireText(query)).isFalse();
            assertThat(wireText(query)).contains(publicPostId)
                    .doesNotContain(publicBody, privateBody,
                            runtime.config().dataDir().toString());
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    private AppConfig config() throws Exception {
        Path factsFile = TestSupport.writeFacts(
                tempDir.resolve("world").resolve("facts.json"), """
                        {"facts": [
                          {"factId": "public-1", "content": "港口迷雾与失踪船队",
                           "visibility": "PUBLIC"}
                        ]}
                        """);
        ObjectNode root = TestSupport.validConfigNode(
                tempDir.resolve("data"), factsFile);
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        return TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot-p6.json"), root));
    }

    private static SecretProvider secrets() {
        return SecretProvider.of(Map.of(
                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN,
                "IIBOT_LLM_KEY", "llm-key"));
    }

    private ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
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

    private static String batchJson(String publicSummary,
                                    Map<String, String> privateOutcomes) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                    "publicSummary", publicSummary,
                    "privateOutcomes", privateOutcomes,
                    "worldChanged", true,
                    "noOpReason", ""));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String publicationJson(String publicNarrative,
                                          Map<String, String> privateOutcomes) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("publicNarrative", publicNarrative);
            value.put("privateOutcomes", privateOutcomes);
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("测试 JSON 构造失败", failure);
        }
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
