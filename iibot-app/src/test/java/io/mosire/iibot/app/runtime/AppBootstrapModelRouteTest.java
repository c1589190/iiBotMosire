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
import io.mosire.iibot.app.mcp.GmAideChatTool;
import io.mosire.iibot.app.mcp.RunAdjudicationAgentTool;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
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

/** AppBootstrap 按 secretary/adjudicator/gmAide 三个 Agent 路由选择独立 LlmClient。 */
@Timeout(60)
class AppBootstrapModelRouteTest {

    private static final String CONTROL_TOKEN = "route-control-token-d88b5f31";
    private static final String CONTROL_PATH = "/route/mcp";

    @TempDir
    Path tempDir;

    @Test
    void threeAgentsUseTheirConfiguredRoutesAndDefaultIsNeverTouched() throws Exception {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"}
                ]}
                """);
        ObjectNode root = TestSupport.validConfigNode(tempDir.resolve("data"), factsFile);
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        ObjectNode routes = root.putObject("llmRoutes");
        routes.set("player-route", llmNode("fake-player", "IIBOT_LLM_KEY"));
        routes.set("adj-route", llmNode("fake-adjudicator", "IIBOT_LLM_KEY"));
        routes.set("aide-route", llmNode("fake-gmAide", "IIBOT_LLM_KEY"));
        ObjectNode agents = root.putObject("agentRoutes");
        agents.put("secretary", "player-route");
        agents.put("adjudicator", "adj-route");
        agents.put("gmAide", "aide-route");
        AppConfig config = TestSupport.loadConfig(
                TestSupport.writeConfig(tempDir.resolve("iibot.json"), root));

        FakeLlmClient defaultLlm = FakeLlmClient.with();
        FakeLlmClient secretaryLlm = FakeLlmClient.with(LlmResponse.text("秘书路由回复"));
        FakeLlmClient adjudicatorLlm = FakeLlmClient.with(
                LlmResponse.toolCall("adj-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "港口迷雾散去。",
                        "playerOutcomes", Map.of("player-1", "你看见北码头。" ),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "north-pier", "value", "investigated")),
                        "nextHooks", List.of("继续调查"))),
                LlmResponse.text("中央推演完成。"));
        FakeLlmClient gmAideLlm = FakeLlmClient.with(LlmResponse.text("GM 推演秘书路由回复"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        SecretProvider secrets = SecretProvider.of(Map.of(
                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                "IIBOT_LLM_KEY", "llm-key-value",
                "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN));

        AppRuntime runtime = AppBootstrap.build(config, BootstrapOptions.fake(
                secrets, Clock.systemUTC(), defaultLlm, adapter,
                Map.of("player-route", secretaryLlm,
                        "adj-route", adjudicatorLlm,
                        "aide-route", gmAideLlm)));
        McpSyncClient client = null;
        try {
            runtime.start();
            client = client(runtime);

            McpSchema.CallToolResult gmChat = client.callTool(new McpSchema.CallToolRequest(
                    GmAideChatTool.NAME, Map.of("message", "只读整理当前世界")));
            assertThat(gmChat.isError()).isFalse();
            assertThat(wireText(gmChat)).isEqualTo("GM 推演秘书路由回复");
            assertThat(gmAideLlm.requests()).hasSize(1);

            assertThat(runtime.submit(TestSupport.privateMessage(
                    "qq-p1", "你好", "route-secretary-1"))).isTrue();
            assertThat(runtime.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(secretaryLlm.requests()).hasSize(1);
            assertThat(adapter.sent()).anySatisfy(message ->
                    assertThat(message.textSegments()).contains("秘书路由回复"));

            callOk(client, "tf_open_turn", Map.of(
                    "expectedRevision", runtime.engine().status().engineRevision(),
                    "idempotencyKey", "route-open",
                    "reason", "路由测试",
                    "minutes", 30));
            Actor player = new Actor("player-1", ActorRole.PLAYER);
            CommandResult draftCreated = runtime.engine().createDecisionDraft(player,
                    new DecisionDraftInput("调查北码头", List.of("询问船工"),
                            Map.of("people", "1"), "北码头", "遇险撤回",
                            java.time.Instant.now().plus(Duration.ofMinutes(30))),
                    runtime.engine().turnView().orElseThrow().revision(), "route-draft");
            assertThat(draftCreated.ok()).isTrue();
            DecisionDraft draft = runtime.engine().draft(draftCreated.referenceId().orElseThrow())
                    .orElseThrow();
            assertThat(runtime.engine().confirmDecision(player, draft.draftId(),
                    draft.previewHash(), runtime.engine().turnView().orElseThrow().revision(),
                    "route-confirm").ok()).isTrue();
            callOk(client, "tf_lock_turn", Map.of(
                    "expectedRevision", runtime.engine().turnView().orElseThrow().revision(),
                    "idempotencyKey", "route-lock",
                    "reason", "路由测试"));
            McpSchema.CallToolResult adjudication = client.callTool(
                    new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, Map.of(
                            "expectedRevision", runtime.engine().turnView().orElseThrow().revision(),
                            "idempotencyKey", "route-adjudication",
                            "reason", "路由测试")));
            assertThat(adjudication.isError()).as(wireText(adjudication)).isFalse();
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.COMMITTED);
            assertThat(adjudicatorLlm.requests()).hasSize(2);
            assertThat(defaultLlm.requests()).isEmpty();
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    private McpSyncClient client(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        ControlMcpServer server = (ControlMcpServer) field.get(runtime);
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

    private static void callOk(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("工具 %s: %s", tool, wireText(result)).isFalse();
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static ObjectNode llmNode(String model, String apiKeyEnv) {
        ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("baseUrl", "http://127.0.0.1:1/v1");
        node.put("model", model);
        node.put("apiKeyEnv", apiKeyEnv);
        node.put("connectTimeoutSeconds", 2);
        node.put("readTimeoutSeconds", 5);
        node.put("echoReasoningContent", false);
        return node;
    }
}
