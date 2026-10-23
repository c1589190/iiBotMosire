package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.RunAdjudicationAgentTool;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.agent.SubmitAdjudicationTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AppBootstrap 控制面接线：19 工具 + 外部发布后经真实分发器出站。 */
@Timeout(60)
class AppBootstrapControlPlaneTest {

    private static final String CONTROL_TOKEN = "bootstrap-control-token-30c6e1ac";
    private static final String CONTROL_PATH = "/control/mcp";

    @TempDir
    Path tempDir;

    @Test
    void controlPlaneExposesAgentToolAndPublishesThroughResultDistributor() throws Exception {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"},
                  {"factId": "director-1", "content": "推演专用秘密", "visibility": "DIRECTOR_ONLY"}
                ]}
                """);
        Path dataDir = tempDir.resolve("data");
        ObjectNode configNode = TestSupport.validConfigNode(dataDir, factsFile);
        ObjectNode control = configNode.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        AppConfig config = TestSupport.loadConfig(
                TestSupport.writeConfig(tempDir.resolve("iibot.json"), configNode));

        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.toolCall("adj-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "船队失踪的线索指向北码头。",
                        "playerOutcomes", Map.of("player-1", "你找到了航海日志。"),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "north-pier", "value", "investigated")),
                        "nextHooks", List.of("航海日志提到暴风前夜"))),
                LlmResponse.text("中央推演完成。"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        SecretProvider secrets = SecretProvider.of(Map.of(
                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN));

        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets, Clock.systemUTC(), llm, adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer controlServer = controlServer(runtime);
            assertThat(controlServer.boundPort()).isPositive();

            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + controlServer.boundPort())
                    .endpoint(CONTROL_PATH)
                    .resumableStreams(false)
                    .connectTimeout(Duration.ofSeconds(5))
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                            request.header("Authorization", "Bearer " + CONTROL_TOKEN))
                    .build();
            client = McpClient.sync(transport).build();
            client.initialize();

            McpSchema.ListToolsResult list = client.listTools();
            assertThat(list.tools().stream().map(McpSchema.Tool::name))
                    .hasSize(19)
                    .contains(RunAdjudicationAgentTool.NAME);

            callOk(client, "tf_open_turn", Map.of(
                    "expectedRevision", runtime.engine().status().engineRevision(),
                    "idempotencyKey", "bootstrap-open",
                    "reason", "AppBootstrap 接线测试",
                    "minutes", 30));

            // 测试装置直接在领域层制造一个已确认决策，让快照包含一名玩家；
            // 生产玩家的硬确认路径仍由 QQ 入站与既有测试覆盖。MCP 不提供确认工具。
            Actor player = new Actor("player-1", ActorRole.PLAYER);
            CommandResult created = runtime.engine().createDecisionDraft(player,
                    new DecisionDraftInput("调查失踪船队", List.of("询问船工"),
                            Map.of("people", "1"), "北码头", "遇险撤回",
                            java.time.Instant.now().plus(Duration.ofMinutes(30))),
                    runtime.engine().turnView().orElseThrow().revision(), "bootstrap-draft");
            assertThat(created.ok()).isTrue();
            DecisionDraft draft = runtime.engine().draft(created.referenceId().orElseThrow())
                    .orElseThrow();
            assertThat(runtime.engine().confirmDecision(player, draft.draftId(),
                    draft.previewHash(), runtime.engine().turnView().orElseThrow().revision(),
                    "bootstrap-confirm").ok()).isTrue();

            long openRevision = runtime.engine().turnView().orElseThrow().revision();
            callOk(client, "tf_lock_turn", Map.of(
                    "expectedRevision", openRevision,
                    "idempotencyKey", "bootstrap-lock",
                    "reason", "AppBootstrap 接线测试"));

            long lockedRevision = runtime.engine().turnView().orElseThrow().revision();
            McpSchema.CallToolResult adjudication = client.callTool(
                    new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, Map.of(
                            "expectedRevision", lockedRevision,
                            "idempotencyKey", "bootstrap-adjudication",
                            "reason", "AppBootstrap 接线测试")));
            assertThat(adjudication.isError())
                    .as("中央推演工具结果: %s", wireText(adjudication)).isFalse();
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.COMMITTED);

            long committedRevision = runtime.engine().turnView().orElseThrow().revision();
            callOk(client, "tf_publish_turn", Map.of(
                    "expectedRevision", committedRevision,
                    "idempotencyKey", "bootstrap-publish",
                    "reason", "AppBootstrap 接线测试"));
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.PUBLISHED);

            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.GROUP
                                    && "group-public".equals(message.targetId())),
                    "MCP 发布后应通过 TurnResultDistributor 发公开叙事");
            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.PRIVATE
                                    && "qq-p1".equals(message.targetId())),
                    "MCP 发布后应通过 TurnResultDistributor 发玩家私聊结果");
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
                assertThat(message.targetId()).isEqualTo("qq-p1");
                assertThat(message.textSegments()).containsExactly("你找到了航海日志。");
            });
            assertThat(llm.requests()).hasSize(2);
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
        assertThat(adapter.isClosed()).isTrue();
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        Object value = field.get(runtime);
        assertThat(value).isInstanceOf(ControlMcpServer.class);
        return (ControlMcpServer) value;
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static void callOk(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("工具 %s 应成功: %s", tool, result).isFalse();
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, interrupted);
            }
        }
        throw new AssertionError(message);
    }
}
