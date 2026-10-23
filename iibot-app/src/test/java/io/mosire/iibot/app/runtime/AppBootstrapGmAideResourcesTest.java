package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.GmAideChatTool;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.agent.GmAideAgent;
import io.mosire.iibot.taskflow.agent.GmAideTools;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
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

/** 组合根把工作区/推文服务注入 GM 推演秘书，且 tf_gm_aide_chat 仍不发送 QQ。 */
@Timeout(60)
class AppBootstrapGmAideResourcesTest {

    private static final String CONTROL_TOKEN = "gm-aide-resources-token-91c4f81d";
    private static final String CONTROL_PATH = "/gm-aide-resources/mcp";

    @TempDir
    Path tempDir;

    @Test
    void gmAideChatReadsWorkspaceAndPostsThroughInjectedServicesWithoutSendingQq()
            throws Exception {
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
        AppConfig config = TestSupport.loadConfig(
                TestSupport.writeConfig(tempDir.resolve("iibot.json"), root));

        FakeLlmClient llm = FakeLlmClient.with();
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
            GmAideAgent gmAideAgent = gmAideAgent(controlServer);
            SecretaryWorkspaceStore workspaceStore = workspaceStore(controlServer);
            PostService postService = postService(controlServer);
            assertThat(gmAideAgent.workspaceStore()).isSameAs(workspaceStore);
            assertThat(gmAideAgent.postService()).isSameAs(postService);
            assertThat(gmAideAgent.tools()).extracting(tool -> tool.name())
                    .containsExactlyInAnyOrderElementsOf(GmAideTools.TOOL_NAMES);

            workspaceStore.writeGmMaterial("player-1", "gm/order.md",
                    "GM 下发：北码头档案在 gm/order.md");
            PostCommandResult privatePost = postService.createDraft(
                    new Actor("player-2", ActorRole.PLAYER), "B 私人标题",
                    "B 玩家私密推文正文", PostVisibility.PRIVATE,
                    postService.collectionRevision("player-2"), "gm-aide-app-private-post");
            assertThat(privatePost.ok()).isTrue();

            llm.enqueue(LlmResponse.toolCall("ws-list", GmAideAgent.GM_WORKSPACE_LIST, Map.of()));
            llm.enqueue(LlmResponse.toolCall("ws-read", GmAideAgent.GM_WORKSPACE_READ,
                    Map.of("playerId", "player-1", "path", "gm/order.md")));
            llm.enqueue(LlmResponse.toolCall("posts", GmAideAgent.GM_POSTS, Map.of()));
            llm.enqueue(LlmResponse.toolCall("post-read", GmAideAgent.GM_POST_READ,
                    Map.of("postId", privatePost.postId())));
            llm.enqueue(LlmResponse.text("GM aide 已读取工作区与全部推文。"));

            int sentBefore = adapter.sent().size();
            client = client(controlServer);
            McpSchema.ListToolsResult list = client.listTools();
            assertThat(list.tools().stream().map(McpSchema.Tool::name))
                    .hasSize(28)
                    .contains(GmAideChatTool.NAME);

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(GmAideChatTool.NAME, Map.of(
                            "message", "读取工作区与全部推文并整理")));
            assertThat(result.isError()).as(wireText(result)).isFalse();
            assertThat(wireText(result)).isEqualTo("GM aide 已读取工作区与全部推文。");
            assertThat(adapter.sent()).hasSize(sentBefore);
            assertThat(allPromptText(llm.requests()))
                    .contains("player-1", "player-2", "gm/order.md")
                    .contains("GM 下发：北码头档案在 gm/order.md")
                    .contains(privatePost.postId(), "B 玩家私密推文正文")
                    .doesNotContain(tempDir.toString(), "qq-p1", "qq-p2", CONTROL_TOKEN);
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
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

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        Object value = field.get(runtime);
        assertThat(value).isInstanceOf(ControlMcpServer.class);
        return (ControlMcpServer) value;
    }

    private static GmAideAgent gmAideAgent(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("gmAideAgent");
        field.setAccessible(true);
        return (GmAideAgent) field.get(server);
    }

    private static SecretaryWorkspaceStore workspaceStore(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("secretaryWorkspaceStore");
        field.setAccessible(true);
        return (SecretaryWorkspaceStore) field.get(server);
    }

    private static PostService postService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("postService");
        field.setAccessible(true);
        return (PostService) field.get(server);
    }

    private static String allPromptText(List<LlmRequest> requests) {
        StringBuilder text = new StringBuilder();
        for (LlmRequest request : requests) {
            request.messages().forEach(message -> message.content().forEach(part ->
                    text.append(part).append('\n')));
        }
        return text.toString();
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }
}
