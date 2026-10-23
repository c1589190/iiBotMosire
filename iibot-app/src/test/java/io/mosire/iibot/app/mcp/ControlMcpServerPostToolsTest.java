package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.agent.AdjudicationService;
import io.mosire.iibot.taskflow.agent.GmAideAgent;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

/** 推文 GM MCP 工具的真实 loopback SDK 测试：列表、读、审批、拒绝、发布与授权元数据。 */
@Timeout(60)
class ControlMcpServerPostToolsTest {

    private static final String PATH = "/control/mcp";
    private static final String TOKEN = "test-post-token-b7e2d441";
    private static final String TOKEN_ENV = "IIBOT_TEST_POST_MCP_TOKEN";
    private static final String BODY_MARKER = "推文正文-仅get可见-4b8a";
    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<McpSyncClient> clients = new ArrayList<>();
    private ControlMcpServer server;
    private PostService postService;

    @AfterEach
    void tearDown() {
        for (McpSyncClient client : clients) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                // 清理失败不影响判定
            }
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void fullAssemblyLists28ToolsAndPostWorkflowUsesStableCodesAndRevisions() throws Exception {
        startFullAssembly();
        McpSyncClient client = initializedClient();

        McpSchema.ListToolsResult list = client.listTools();
        List<String> names = list.tools().stream().map(McpSchema.Tool::name).toList();
        assertThat(names).hasSize(28)
                .containsAll(GmPostMcpTools.NAMES)
                .doesNotContain("tf_qq_send", "qq_send", "tf_confirm_decision", "shutdown");

        List<AgentTool> tools = GmPostMcpTools.all(postService,
                new Actor("external-supervisor", ActorRole.SUPERVISOR));
        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyElementsOf(GmPostMcpTools.NAMES);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly("supervisor").doesNotContain("*", "SYSTEM");
            assertThat(tool.resources().namespaces())
                    .containsExactly(PostStore.RESOURCE_NAMESPACE);
            assertThat(tool.resources().policyFor(PostStore.RESOURCE_NAMESPACE))
                    .isEqualTo(ResourcePolicy.DENY);
            assertThat(tool.spec().requiredLevel()).isNotEqualTo(AccessToken.SYSTEM);
            assertThat(tool.jsonSchema().toString()).doesNotContain("*", "SYSTEM");
        });
        assertThat(find(tools, GmPostListTool.NAME).spec().destructive()).isFalse();
        assertThat(find(tools, GmPostGetTool.NAME).spec().destructive()).isFalse();
        assertThat(find(tools, GmPostDecideTool.NAME).spec().destructive()).isTrue();
        assertThat(find(tools, GmPostPublishTool.NAME).spec().destructive()).isTrue();
        assertThat(find(tools, GmPostDecideTool.NAME).jsonSchema().toString())
                .contains("expectedRevision", "idempotencyKey", "reason");

        PostCommandResult created = postService.createDraft(
                new Actor("player-1", ActorRole.PLAYER), "北码头情报", BODY_MARKER,
                PostVisibility.PUBLIC, 0, "post-create-public");
        assertThat(created.ok()).isTrue();
        String postId = created.postId();

        McpSchema.CallToolResult pending = client.callTool(new McpSchema.CallToolRequest(
                GmPostListTool.NAME, Map.of("status", "PENDING_APPROVAL")));
        assertThat(pending.isError()).isFalse();
        JsonNode pendingJson = JSON.readTree(wireText(pending));
        assertThat(pendingJson.path("data").path("count").asInt()).isEqualTo(1);
        assertThat(wireText(pending)).contains(postId, "PENDING_APPROVAL")
                .doesNotContain(BODY_MARKER);

        McpSchema.CallToolResult got = client.callTool(new McpSchema.CallToolRequest(
                GmPostGetTool.NAME, Map.of("postId", postId)));
        assertThat(got.isError()).isFalse();
        assertThat(wireText(got)).contains(BODY_MARKER, "\"revision\":1");

        Map<String, Object> approveArgs = Map.of(
                "postId", postId,
                "decision", "APPROVE",
                "expectedRevision", 1L,
                "idempotencyKey", "post-approve-once",
                "reason", "GM 审核通过");
        McpSchema.CallToolResult approved = client.callTool(
                new McpSchema.CallToolRequest(GmPostDecideTool.NAME, approveArgs));
        assertThat(approved.isError()).as(wireText(approved)).isFalse();
        assertThat(wireText(approved)).contains("\"status\":\"APPROVED\"").contains("\"revision\":2")
                .doesNotContain(BODY_MARKER);
        McpSchema.CallToolResult replay = client.callTool(
                new McpSchema.CallToolRequest(GmPostDecideTool.NAME, approveArgs));
        assertThat(replay.isError()).isFalse();
        assertThat(wireText(replay)).isEqualTo(wireText(approved));

        McpSchema.CallToolResult repeatedDifferentKey = client.callTool(
                new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                        "postId", postId,
                        "decision", "APPROVE",
                        "expectedRevision", 2L,
                        "idempotencyKey", "post-approve-twice")));
        assertThat(repeatedDifferentKey.isError()).isTrue();
        assertThat(wireText(repeatedDifferentKey)).contains("INVALID_STATUS", "\"domainCode\":\"INVALID_STATUS\"");

        McpSchema.CallToolResult wrongRevision = client.callTool(
                new McpSchema.CallToolRequest(GmPostPublishTool.NAME, Map.of(
                        "postId", postId,
                        "expectedRevision", 99L,
                        "idempotencyKey", "post-publish-wrong")));
        assertThat(wrongRevision.isError()).isTrue();
        assertThat(wireText(wrongRevision)).contains("REVISION_CONFLICT")
                .doesNotContain(BODY_MARKER);

        McpSchema.CallToolResult published = client.callTool(
                new McpSchema.CallToolRequest(GmPostPublishTool.NAME, Map.of(
                        "postId", postId,
                        "expectedRevision", 2L,
                        "idempotencyKey", "post-publish-once")));
        assertThat(published.isError()).as(wireText(published)).isFalse();
        assertThat(wireText(published)).contains("\"status\":\"PUBLISHED\"",
                        "\"deliveryState\":\"PENDING\"", "\"revision\":3")
                .doesNotContain(BODY_MARKER);
        assertThat(postService.post(postId).orElseThrow().status())
                .isEqualTo(PostStatus.PUBLISHED);

        PostCommandResult second = postService.createDraft(
                new Actor("player-1", ActorRole.PLAYER), "待拒绝", "拒绝正文不能泄露",
                PostVisibility.PRIVATE, postService.collectionRevision("player-1"),
                "post-create-private");
        assertThat(second.ok()).isTrue();
        McpSchema.CallToolResult rejectWithoutReason = client.callTool(
                new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                        "postId", second.postId(),
                        "decision", "REJECT",
                        "expectedRevision", 1L,
                        "idempotencyKey", "post-reject-no-reason")));
        assertThat(rejectWithoutReason.isError()).isTrue();
        assertThat(wireText(rejectWithoutReason)).contains("INVALID_ARGUMENTS");
        McpSchema.CallToolResult rejected = client.callTool(
                new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                        "postId", second.postId(),
                        "decision", "REJECT",
                        "expectedRevision", 1L,
                        "idempotencyKey", "post-reject-with-reason",
                        "reason", "信息不足")));
        assertThat(rejected.isError()).as(wireText(rejected)).isFalse();
        assertThat(wireText(rejected)).contains("\"status\":\"REJECTED\"");

        McpSchema.CallToolResult built = client.callTool(new McpSchema.CallToolRequest(
                GmPostListTool.NAME, Map.of("status", "PUBLISHED")));
        assertThat(built.isError()).isFalse();
        assertThat(wireText(built)).contains(postId).doesNotContain(BODY_MARKER);
    }

    @Test
    void secretaryAndPlayerRolesCannotCallPostToolsThroughAuthorizer() {
        PostService service = new PostService(new InMemoryPostStore(),
                Clock.fixed(BASE, ZoneOffset.UTC));
        Actor supervisor = new Actor("external-supervisor", ActorRole.SUPERVISOR);
        List<AgentTool> tools = GmPostMcpTools.all(service, supervisor);

        assertThat(callAsRole(tools, "secretary", GmPostListTool.NAME, Map.of()).code())
                .isEqualTo("PERMISSION_DENIED");
        assertThat(callAsRole(tools, "player", GmPostPublishTool.NAME, Map.of(
                "postId", "missing", "expectedRevision", 1L,
                "idempotencyKey", "player-publish")).code())
                .isEqualTo("PERMISSION_DENIED");
        assertThat(callAsRole(tools, "supervisor", GmPostListTool.NAME, Map.of()).success())
                .isTrue();
    }

    private void startFullAssembly() throws Exception {
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TaskflowEngine engine = new TaskflowEngine(clock, eventStore);
        postService = new PostService(new InMemoryPostStore(), clock);
        SecretaryWorkspaceStore workspaceStore = SecretaryWorkspaceStore.open(
                tempDirectory(), List.of("player-1"));
        SecretarySessionRegistry sessions = new SecretarySessionRegistry();
        FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("只读摘要"));
        server = ControlMcpServer.builder(engine, new InMemoryEventReadPort(eventStore),
                        new Actor("external-supervisor", ActorRole.SUPERVISOR),
                        ControlMcpConfig.enabled("127.0.0.1", 0, PATH, TOKEN_ENV))
                .secretProvider(SecretProvider.of(Map.of(TOKEN_ENV, TOKEN)))
                .clock(clock)
                .adjudicationService(new AdjudicationService(engine, llm))
                .gmAideAgent(new GmAideAgent(engine, llm, new InMemoryEventReadPort(eventStore)))
                .secretaryWorkspaceStore(workspaceStore)
                .secretarySessions(sessions)
                .postService(postService)
                .build();
        server.start();
    }

    @TempDir
    Path tempDir;

    private Path tempDirectory() {
        return tempDir;
    }

    private McpSyncClient initializedClient() {
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

    private static ToolResult callAsRole(List<AgentTool> tools, String role,
                                         String toolName, Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        Set<String> allowed = new LinkedHashSet<>(GmPostMcpTools.NAMES);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allowed.toArray(String[]::new))
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        PostStore.RESOURCE_NAMESPACE,
                        ResourceScope.of(PostStore.RESOURCE_NAMESPACE))))
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), arguments, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name()))
                .findFirst().orElseThrow();
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
