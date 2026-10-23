package io.mosire.iibot.app.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.agent.AdjudicationService;
import io.mosire.iibot.taskflow.agent.GmAideAgent;
import io.mosire.iibot.taskflow.control.ExternalControlToolSet;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 注入 GM 推演秘书后，官方 MCP SDK 真 socket 的 20 工具与只读对话测试。 */
@Timeout(60)
class ControlMcpServerGmAideTest {

    private static final String PATH = "/control/mcp";
    private static final String TOKEN = "gm-aide-control-token-a7d4c2f1";
    private static final String TOKEN_ENV = "IIBOT_GM_AIDE_TEST_TOKEN";
    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");

    private final List<McpSyncClient> clients = new ArrayList<>();
    private ControlMcpServer server;
    private TaskflowEngine engine;
    private FakeLlmClient llm;
    private GmAideAgent gmAideAgent;

    @AfterEach
    void tearDown() {
        for (McpSyncClient client : clients) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                // 清理失败不影响主判定
            }
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void officialClientListsTwentyToolsAndCallsGmAideChat() throws Exception {
        startServer();
        long revisionBefore = engine.status().engineRevision();
        long worldVersionBefore = engine.status().worldVersion();
        McpSyncClient client = client();

        McpSchema.ListToolsResult list = client.listTools();
        List<String> names = list.tools().stream().map(McpSchema.Tool::name).toList();
        assertThat(names)
                .hasSize(20)
                .containsAll(ExternalControlToolSet.READ_TOOL_NAMES)
                .containsAll(ExternalControlToolSet.WRITE_TOOL_NAMES)
                .contains(RunAdjudicationAgentTool.NAME, GmAideChatTool.NAME)
                .doesNotContain("qq_send", "shell", "exec", "doc_create", "doc_edit",
                        "tf_confirm_decision");

        McpSchema.Tool listed = list.tools().stream()
                .filter(tool -> GmAideChatTool.NAME.equals(tool.name()))
                .findFirst().orElseThrow();
        assertThat(listed.description()).isNotBlank();
        assertThat(listed.inputSchema()).containsKeys("type", "properties", "required");

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(GmAideChatTool.NAME,
                        Map.of("message", "请整理全量世界资料并推演动向")));
        assertThat(result.isError()).isFalse();
        assertThat(wireText(result)).isEqualTo("只读 GM 摘要");
        assertThat(llm.requests()).hasSize(1);
        assertThat(engine.status().engineRevision()).isEqualTo(revisionBefore);
        assertThat(engine.status().worldVersion()).isEqualTo(worldVersionBefore);
        assertThat(wireText(result))
                .doesNotContain("系统提示", "prompt", "apiKey", TOKEN);
    }

    @Test
    void chatToolMetadataAndCallerAllowListAreExactAndReadOnly() throws Exception {
        startServer();
        GmAideChatTool tool = new GmAideChatTool(gmAideAgent);
        assertThat(tool.roles()).containsExactly("supervisor");
        assertThat(tool.resources().namespaces()).containsExactly("taskflow");
        assertThat(tool.resources().policyFor("taskflow")).isEqualTo(ResourcePolicy.DENY);
        assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
        assertThat(tool.spec().sensitive()).isFalse();
        assertThat(tool.spec().destructive()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.jsonSchema().get("properties");
        assertThat(properties).containsOnlyKeys("message");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.jsonSchema().get("required");
        assertThat(required).containsExactly("message");

        Method contextMethod = ControlMcpServer.class.getDeclaredMethod("controlCallerContext");
        contextMethod.setAccessible(true);
        ToolContext caller = (ToolContext) contextMethod.invoke(server);
        Set<String> expected = new LinkedHashSet<>();
        expected.addAll(ExternalControlToolSet.READ_TOOL_NAMES);
        expected.addAll(ExternalControlToolSet.WRITE_TOOL_NAMES);
        expected.add(RunAdjudicationAgentTool.NAME);
        expected.add(GmAideChatTool.NAME);
        assertThat(caller.permissions().allowedTools()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(caller.permissions().allowedTools()).doesNotContain(AgentPermissionSet.ALL_TOOLS);
        assertThat(caller.permissions().roles().roles()).containsExactly("supervisor");

        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(GmAideChatTool.NAME)
                .roles(RoleSet.of("supervisor"))
                .resourceScopes(ResourceScopeMap.of("taskflow", ResourceScope.unlimited()))
                .build();
        ToolContext badArgs = new ToolContext(AccessToken.DEFAULT, permissions, Map.of(),
                Map.of(), io.mosire.agentlib.permission.AgentIdentity.external());
        ToolResult missing = ToolCallAuthorizer.standard()
                .execute(registry, GmAideChatTool.NAME, badArgs);
        assertThat(missing.code()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(llm.requests()).isEmpty();
    }

    private void startServer() {
        InMemoryEventStore store = new InMemoryEventStore();
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        engine = new TaskflowEngine(clock, store);
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        Actor player = new Actor("player-1", ActorRole.PLAYER);
        ok(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "gm-aide-reg-director"));
        ok(engine.registerPlayer(director,
                new PlayerProfile("player-1", "qq-p1", "Player One"),
                engine.status().engineRevision(), "gm-aide-reg-player"));
        ok(engine.loadWorldFacts(director, 0,
                List.of(new WorldFact("director-only", 1, "GM 可见的秘密", Visibility.DIRECTOR_ONLY)),
                engine.status().engineRevision(), "gm-aide-load-facts"));
        llm = FakeLlmClient.with(LlmResponse.text("只读 GM 摘要"));
        gmAideAgent = new GmAideAgent(engine, llm, new InMemoryEventReadPort(store));
        Actor supervisor = new Actor("external-supervisor", ActorRole.SUPERVISOR);
        server = ControlMcpServer.builder(engine, new InMemoryEventReadPort(store), supervisor,
                        ControlMcpConfig.enabled("127.0.0.1", 0, PATH, TOKEN_ENV))
                .secretProvider(SecretProvider.of(Map.of(TOKEN_ENV, TOKEN)))
                .clock(clock)
                .adjudicationService(new AdjudicationService(engine, FakeLlmClient.with()))
                .gmAideAgent(gmAideAgent)
                .build();
        server.start();
    }

    private McpSyncClient client() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.boundPort())
                .endpoint(PATH)
                .resumableStreams(false)
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        clients.add(client);
        client.initialize();
        return client;
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static void ok(CommandResult result) {
        if (!result.ok()) {
            throw new AssertionError("fixture 命令失败: " + result.code() + " " + result.message());
        }
    }
}
