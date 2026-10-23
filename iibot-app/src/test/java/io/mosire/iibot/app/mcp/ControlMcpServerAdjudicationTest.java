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
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.agent.AdjudicationService;
import io.mosire.iibot.taskflow.agent.SubmitAdjudicationTool;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 可选中央推演 Agent MCP 工具与发布后分发钩子的真实 socket 测试。 */
@Timeout(60)
class ControlMcpServerAdjudicationTest {

    private static final String PATH = "/control/mcp";
    private static final String TOKEN = "test-adjudication-token-6cc0d2b1";
    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final List<McpSyncClient> clients = new ArrayList<>();
    private ControlMcpServer server;

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
    void injectedToolIsListedAndRunsLockedTurnToCommittedThroughOfficialClient() throws Exception {
        Fixture fixture = lockedFixture(adjudicationLlm());
        AtomicInteger afterMutation = new AtomicInteger();
        startServer(fixture, afterMutation::incrementAndGet);
        McpSyncClient client = initializedClient();

        McpSchema.ListToolsResult list = client.listTools();
        List<String> names = list.tools().stream().map(McpSchema.Tool::name).toList();
        assertThat(names).hasSize(19).contains(RunAdjudicationAgentTool.NAME);
        McpSchema.Tool listed = list.tools().stream()
                .filter(tool -> RunAdjudicationAgentTool.NAME.equals(tool.name()))
                .findFirst().orElseThrow();
        assertThat(listed.description()).isNotBlank();
        assertThat(listed.inputSchema()).containsKeys("type", "properties", "required");

        RunAdjudicationAgentTool direct = new RunAdjudicationAgentTool(
                fixture.engine(), fixture.service(), fixture.supervisor());
        assertThat(direct.roles()).containsExactly("supervisor");
        assertThat(direct.resources().namespaces()).containsExactly("taskflow");
        assertThat(direct.resources().policyFor("taskflow")).isEqualTo(ResourcePolicy.DENY);
        assertThat(direct.spec().requiredLevel()).isEqualTo(AccessToken.DEFAULT);
        assertThat(direct.spec().sensitive()).isTrue();
        assertThat(direct.spec().destructive()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) direct.jsonSchema().get("properties");
        assertThat(properties).containsKeys("expectedRevision", "idempotencyKey", "reason");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) direct.jsonSchema().get("required");
        assertThat(required).containsExactlyInAnyOrder(
                "expectedRevision", "idempotencyKey", "reason");

        McpSchema.CallToolResult status = client.callTool(
                new McpSchema.CallToolRequest("tf_status", Map.of()));
        assertThat(status.isError()).isFalse();
        assertThat(wireText(status)).contains("\"tool\":\"tf_status\"");

        long turnRevision = fixture.turn().revision();
        long worldBefore = fixture.engine().status().worldVersion();
        Map<String, Object> args = Map.of(
                "expectedRevision", turnRevision,
                "idempotencyKey", "run-agent-once",
                "reason", "MCP 集成测试：运行中央推演 Agent");
        McpSchema.CallToolResult run = client.callTool(
                new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, args));
        assertThat(run.isError()).isFalse();
        JsonNode runJson = JSON.readTree(wireText(run));
        assertThat(runJson.path("ok").asBoolean()).isTrue();
        assertThat(runJson.path("data").path("success").asBoolean()).isTrue();
        assertThat(runJson.path("data").path("state").asText()).isEqualTo("COMMITTED");
        assertThat(runJson.path("data").path("toolCallCount").asInt()).isEqualTo(1);
        assertThat(runJson.path("data").path("message").asText()).doesNotContain("船队");
        assertThat(fixture.engine().turnView().orElseThrow().state()).isEqualTo(TurnState.COMMITTED);
        assertThat(fixture.engine().status().worldVersion()).isEqualTo(worldBefore + 1);
        assertThat(fixture.llm().requests()).hasSize(2);

        // 同一幂等键直接返回首次 ToolResult，不再次运行 LLM。
        McpSchema.CallToolResult duplicate = client.callTool(
                new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, args));
        assertThat(duplicate.isError()).isFalse();
        assertThat(wireText(duplicate)).isEqualTo(wireText(run));
        assertThat(fixture.llm().requests()).hasSize(2);

        long publishRevision = fixture.turn().revision();
        int beforePublish = afterMutation.get();
        McpSchema.CallToolResult publish = client.callTool(new McpSchema.CallToolRequest(
                "tf_publish_turn", Map.of(
                        "expectedRevision", publishRevision,
                        "idempotencyKey", "publish-after-agent",
                        "reason", "MCP 集成测试：发布后触发分发")));
        assertThat(publish.isError()).isFalse();
        assertThat(fixture.engine().turnView().orElseThrow().state())
                .isEqualTo(TurnState.PUBLISHED);
        await(() -> afterMutation.get() > beforePublish,
                "tf_publish_turn 成功后 afterMutation 应被调用");
    }

    @Test
    void serviceFailureReturnsStableCodeWithoutFakeCommitAndCachesFailure() throws Exception {
        Fixture fixture = lockedFixture(FakeLlmClient.with(LlmResponse.text("我不提交。")));
        startServer(fixture, () -> { });
        McpSyncClient client = initializedClient();
        long worldBefore = fixture.engine().status().worldVersion();
        Map<String, Object> args = Map.of(
                "expectedRevision", fixture.turn().revision(),
                "idempotencyKey", "agent-failure-once",
                "reason", "验证失败不伪造提交");

        McpSchema.CallToolResult failed = client.callTool(
                new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, args));
        assertThat(failed.isError()).isTrue();
        JsonNode failedJson = wireJson(failed);
        assertThat(failedJson.path("code").asText()).isEqualTo("ADJUDICATION_FAILED");
        assertThat(failedJson.path("data").path("success").asBoolean()).isFalse();
        assertThat(failedJson.path("data").path("state").asText())
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(fixture.engine().turnView().orElseThrow().committedProposal()).isNull();
        assertThat(fixture.engine().status().worldVersion()).isEqualTo(worldBefore);
        assertThat(fixture.llm().requests()).hasSize(1);

        McpSchema.CallToolResult duplicate = client.callTool(
                new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, args));
        assertThat(duplicate.isError()).isTrue();
        assertThat(wireText(duplicate)).isEqualTo(wireText(failed));
        assertThat(fixture.llm().requests()).hasSize(1);
    }

    @Test
    void invalidArgumentsAndWrongRevisionAreRejectedBeforeServiceWithZeroSideEffects()
            throws Exception {
        Fixture fixture = lockedFixture(FakeLlmClient.with());
        startServer(fixture, () -> { });
        McpSyncClient client = initializedClient();
        long revision = fixture.turn().revision();
        long worldBefore = fixture.engine().status().worldVersion();

        // SDK 在入站 schema 校验阶段会先拒掉缺失必填字段；工具自身的
        // INVALID_ARGUMENTS 契约用同一 ToolCallAuthorizer 入口直接钉住。
        RunAdjudicationAgentTool direct = new RunAdjudicationAgentTool(
                fixture.engine(), fixture.service(), fixture.supervisor());
        ToolRegistry registry = new ToolRegistry();
        registry.register(direct);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(RunAdjudicationAgentTool.NAME)
                .sensitiveAllowed(true)
                .destructiveAllowed(true)
                .roles(RoleSet.of("supervisor"))
                .resourceScopes(ResourceScopeMap.of("taskflow", ResourceScope.unlimited()))
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions, Map.of(),
                Map.of("expectedRevision", revision, "idempotencyKey", "missing-reason"),
                AgentIdentity.external());
        ToolResult missingReason = ToolCallAuthorizer.standard()
                .execute(registry, RunAdjudicationAgentTool.NAME, context);
        assertThat(missingReason.code()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(missingReason.message()).contains("reason");

        McpSchema.CallToolResult wrongRevision = client.callTool(
                new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, Map.of(
                        "expectedRevision", revision + 1,
                        "idempotencyKey", "wrong-revision",
                        "reason", "不应调用 service")));
        assertThat(wrongRevision.isError()).isTrue();
        assertThat(wireText(wrongRevision)).contains("REVISION_CONFLICT");
        assertThat(fixture.llm().requests()).isEmpty();
        assertThat(fixture.turn().state()).isEqualTo(TurnState.LOCKED);
        assertThat(fixture.engine().status().worldVersion()).isEqualTo(worldBefore);
    }

    @Test
    void wrongTurnStateIsRejectedBeforeServiceWithZeroSideEffects() throws Exception {
        Fixture fixture = openFixture(FakeLlmClient.with());
        startServer(fixture, () -> { });
        McpSyncClient client = initializedClient();
        long worldBefore = fixture.engine().status().worldVersion();

        McpSchema.CallToolResult run = client.callTool(
                new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, Map.of(
                        "expectedRevision", fixture.turn().revision(),
                        "idempotencyKey", "open-state",
                        "reason", "OPEN 状态不应运行推演")));
        assertThat(run.isError()).isTrue();
        assertThat(wireText(run)).contains("WRONG_STATE");
        assertThat(fixture.llm().requests()).isEmpty();
        assertThat(fixture.turn().state()).isEqualTo(TurnState.OPEN);
        assertThat(fixture.engine().status().worldVersion()).isEqualTo(worldBefore);
    }

    @Test
    void afterMutationOnlyRunsAfterSuccessfulPostAndHookFailureIsIsolated() throws Exception {
        Fixture fixture = lockedFixture(FakeLlmClient.with());
        AtomicInteger calls = new AtomicInteger();
        startServer(fixture, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("hook detail must not leak");
        });
        McpSyncClient client = initializedClient();
        assertThat(calls.get()).isGreaterThan(0);

        int beforeRejected = calls.get();
        HttpResponse<String> unauthorized = rawPost("Bearer definitely-wrong", initializeBody(9));
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(calls.get()).isEqualTo(beforeRejected);

        McpSchema.CallToolResult status = client.callTool(
                new McpSchema.CallToolRequest("tf_status", Map.of()));
        assertThat(status.isError()).isFalse();
        assertThat(wireText(status)).contains("\"tool\":\"tf_status\"");
    }

    // ------------------------------------------------------------------
    // 真实 TaskflowEngine fixture
    // ------------------------------------------------------------------

    private static Fixture openFixture(FakeLlmClient llm) {
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        InMemoryEventStore store = new InMemoryEventStore();
        TaskflowEngine engine = new TaskflowEngine(clock, store);
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        Actor supervisor = new Actor("external-supervisor", ActorRole.SUPERVISOR);
        Actor player = new Actor("player-1", ActorRole.PLAYER);
        ok(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "register-director"));
        ok(engine.registerPlayer(director,
                new PlayerProfile("player-1", "qq-p1", "Player One"),
                engine.status().engineRevision(), "register-player"));
        ok(engine.loadWorldFacts(director, engine.status().worldVersion(),
                List.of(new WorldFact("public-1", 1, "港口迷雾", Visibility.PUBLIC)),
                engine.status().engineRevision(), "load-facts"));
        ok(engine.openTurn(director, engine.status().engineRevision(), "open-turn",
                clock.instant().plus(Duration.ofHours(2))));
        return new Fixture(engine, new AdjudicationService(engine, llm),
                supervisor, llm, new InMemoryEventReadPort(store));
    }

    private static Fixture lockedFixture(FakeLlmClient llm) {
        Fixture fixture = openFixture(llm);
        TaskflowEngine engine = fixture.engine();
        Actor player = new Actor("player-1", ActorRole.PLAYER);
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        DecisionDraftInput input = new DecisionDraftInput(
                "调查失踪船队", List.of("询问船工", "搜查仓库"),
                Map.of("people", "3"), "北码头", "遇到危险立即撤回",
                BASE.plus(Duration.ofMinutes(90)));
        CommandResult created = ok(engine.createDecisionDraft(player, input,
                fixture.turn().revision(), "create-draft"));
        DecisionDraft draft = engine.draft(created.referenceId().orElseThrow()).orElseThrow();
        ok(engine.confirmDecision(player, draft.draftId(), draft.previewHash(),
                fixture.turn().revision(), "confirm-draft"));
        ok(engine.lockTurn(director, fixture.turn().revision(), "lock-turn"));
        return new Fixture(engine, fixture.service(), fixture.supervisor(), llm,
                fixture.eventReadPort());
    }

    private static FakeLlmClient adjudicationLlm() {
        return FakeLlmClient.with(
                LlmResponse.toolCall("adj-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "船队失踪的线索指向北码头。",
                        "playerOutcomes", Map.of("player-1", "你找到了航海日志。"),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "north-pier", "value", "investigated")),
                        "nextHooks", List.of("航海日志提到暴风前夜"))),
                LlmResponse.text("LLM 原文不应进入工具结果。"));
    }

    private static CommandResult ok(CommandResult result) {
        if (!result.ok()) {
            throw new AssertionError("预期成功，实际 " + result.code() + ": " + result.message());
        }
        return result;
    }

    private record Fixture(TaskflowEngine engine, AdjudicationService service,
                           Actor supervisor, FakeLlmClient llm,
                           InMemoryEventReadPort eventReadPort) {
        TurnView turn() {
            return engine.turnView().orElseThrow();
        }
    }

    // ------------------------------------------------------------------
    // MCP/HTTP 测试装置
    // ------------------------------------------------------------------

    private void startServer(Fixture fixture, Runnable afterMutation) {
        ControlMcpConfig config = ControlMcpConfig.enabled(
                "127.0.0.1", 0, PATH, "IIBOT_TEST_ADJUDICATION_MCP_TOKEN");
        server = ControlMcpServer.builder(fixture.engine(), fixture.eventReadPort(),
                        fixture.supervisor(), config)
                .secretProvider(SecretProvider.of(
                        Map.of("IIBOT_TEST_ADJUDICATION_MCP_TOKEN", TOKEN)))
                .clock(Clock.fixed(BASE, ZoneOffset.UTC))
                .adjudicationService(fixture.service())
                .afterMutation(afterMutation)
                .build();
        server.start();
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

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static JsonNode wireJson(McpSchema.CallToolResult result) throws Exception {
        String text = wireText(result);
        int newline = text.indexOf('\n');
        if (text.startsWith("[mosire:code=") && newline >= 0) {
            text = text.substring(newline + 1);
        }
        return JSON.readTree(text);
    }

    private HttpResponse<String> rawPost(String authorization, String body) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.boundPort() + PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new AssertionError("裸 HTTP 请求失败", failure);
        }
    }

    private static String initializeBody(int id) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"raw-adjudication-test","version":"1.0"}
                }}
                """.formatted(id);
    }

    private static void await(BooleanSupplier condition, String message) {
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
