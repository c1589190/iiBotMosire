package io.mosire.iibot.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.control.EventReadPort;
import io.mosire.iibot.taskflow.control.ExternalControlToolSet;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import java.io.InputStream;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 真 socket 的 Bearer 控制 MCP 传输测试。
 *
 * <p>证据分两层：①官方 MCP SDK client 经真实 HTTP 完成 initialize → tools/list →
 * tools/call；②裸 JDK HTTP 直接钉住 401、精确路径、请求体上限与 GET SSE 代理头。</p>
 */
@Timeout(60)
class ControlMcpServerTest {

    private static final String PATH = "/control/mcp";
    private static final String TOKEN = "test-bearer-token-7b5e1c9a";
    private static final String TOKEN_ENV = "IIBOT_TEST_CONTROL_MCP_TOKEN";
    private static final String INTERNAL_SECRET = "internal-secret-that-must-never-leak";

    private static final HttpClient RAW_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final List<McpSyncClient> clients = new ArrayList<>();
    private ControlMcpServer server;
    private TaskflowEngine engine;
    private InMemoryEventStore eventStore;
    private EventReadPort eventReadPort;
    private Actor supervisor;

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
        server = null;
    }

    @Test
    void officialClientCompletesInitializeListStatusAndWriteThroughBearerProxy() {
        startServer(controlConfig().withMaxRequestBytes(16 * 1024 * 1024),
                Map.of(TOKEN_ENV, TOKEN));
        McpSyncClient client = newClient("Bearer " + TOKEN);

        McpSchema.InitializeResult initialized = client.initialize();
        assertThat(initialized.serverInfo().name()).isEqualTo("iibot-control");
        assertThat(initialized.serverInfo().version()).isEqualTo("0.1.0");

        McpSchema.ListToolsResult list = client.listTools();
        List<String> names = list.tools().stream().map(McpSchema.Tool::name).toList();
        assertThat(names)
                .hasSize(18)
                .containsAll(ExternalControlToolSet.READ_TOOL_NAMES)
                .containsAll(ExternalControlToolSet.WRITE_TOOL_NAMES)
                .doesNotContain("decision_confirm", "confirm_decision", "shutdown", "qq_send", "shell")
                .doesNotContain("tf_confirm_decision", "tf_shutdown", "tf_qq_send", "tf_shell");

        McpSchema.CallToolResult status = client.callTool(
                new McpSchema.CallToolRequest("tf_status", Map.of()));
        assertThat(status.isError()).isFalse();
        assertThat(wireText(status)).contains("\"tool\":\"tf_status\"").contains("\"engineRevision\":0");

        Map<String, Object> registerArgs = Map.of(
                "expectedRevision", 0L,
                "idempotencyKey", "register-player-1",
                "reason", "Bearer MCP test",
                "qqId", "10001",
                "playerId", "player-1",
                "displayName", "Player One");
        McpSchema.CallToolResult register = client.callTool(
                new McpSchema.CallToolRequest(ExternalControlToolSet.TF_REGISTER_PLAYER, registerArgs));
        assertThat(register.isError()).isFalse();
        assertThat(wireText(register)).contains("\"ok\":true").contains("\"tool\":\"tf_register_player\"");
        assertThat(engine.status().playerCount()).isEqualTo(1);
        assertThat(engine.status().engineRevision()).isEqualTo(1);

        McpSchema.CallToolResult settingList = client.callTool(
                new McpSchema.CallToolRequest(ExternalControlToolSet.TF_SETTING_LIST, Map.of()));
        assertThat(settingList.isError()).isFalse();
        assertThat(wireText(settingList))
                .contains("\"tool\":\"tf_setting_list\"")
                .contains("\"count\":0");

        Map<String, Object> settingArgs = Map.of(
                "expectedRevision", engine.status().engineRevision(),
                "idempotencyKey", "mcp-setting-propose-1",
                "reason", "Bearer MCP setting write test",
                "content", "MCP 写工具提交的海上风暴设定",
                "visibility", "PUBLIC");
        McpSchema.CallToolResult settingPropose = client.callTool(
                new McpSchema.CallToolRequest(ExternalControlToolSet.TF_SETTING_PROPOSE, settingArgs));
        assertThat(settingPropose.isError()).isFalse();
        assertThat(wireText(settingPropose))
                .contains("\"tool\":\"tf_setting_propose\"")
                .contains("\"status\":\"PENDING\"");
        assertThat(engine.pendingSettings()).hasSize(1);
        assertThat(engine.status().worldVersion()).isZero();

        McpSchema.CallToolResult pendingAfterPropose = client.callTool(
                new McpSchema.CallToolRequest(ExternalControlToolSet.TF_SETTING_LIST, Map.of()));
        assertThat(pendingAfterPropose.isError()).isFalse();
        assertThat(wireText(pendingAfterPropose))
                .contains(engine.pendingSettings().getFirst().proposalId())
                .contains("\"count\":1");
    }

    @Test
    void missingAndWrongBearerFailInitializeAndRawHttpReturns401() {
        startServer(controlConfig(), Map.of(TOKEN_ENV, TOKEN));

        HttpResponse<String> missing = rawInitialize(PATH, null);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.headers().firstValue("WWW-Authenticate")).isPresent();

        HttpResponse<String> wrong = rawInitialize(PATH, "Bearer wrong-token");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.headers().firstValue("WWW-Authenticate").orElseThrow())
                .containsIgnoringCase("Bearer");

        McpSyncClient noAuth = newClient(null);
        assertThatThrownBy(noAuth::initialize).isInstanceOf(RuntimeException.class);

        McpSyncClient badAuth = newClient("Bearer definitely-wrong");
        assertThatThrownBy(badAuth::initialize).isInstanceOf(RuntimeException.class);
    }

    @Test
    void bearerComparisonRejectsCaseWhitespaceAndPrefixSuffixVariants() {
        startServer(controlConfig(), Map.of(TOKEN_ENV, TOKEN));

        List<String> rejected = List.of(
                "bearer " + TOKEN,
                "BEARER " + TOKEN,
                "Bearer  " + TOKEN,
                "Bearer " + TOKEN + "suffix",
                "Bearer prefix" + TOKEN,
                "Bearer",
                TOKEN);
        for (String authorization : rejected) {
            HttpResponse<String> response = rawInitialize(PATH, authorization);
            assertThat(response.statusCode())
                    .as("Authorization 变体必须拒绝: <%s>", authorization)
                    .isEqualTo(401);
        }

        // 精确值仍可用：同一请求体应当被代理到内部 MCP。
        HttpResponse<String> accepted = rawInitialize(PATH, "Bearer " + TOKEN);
        assertThat(accepted.statusCode()).isEqualTo(200);
        String sessionId = accepted.headers().firstValue("Mcp-Session-Id").orElseThrow();

        // POST 的 JSON-RPC 响应必须经代理保持 SSE（不是被缓存成普通 JSON）。
        HttpResponse<String> toolsList = rawSessionPost(PATH, "Bearer " + TOKEN, sessionId,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(toolsList.statusCode()).isEqualTo(200);
        assertThat(toolsList.headers().firstValue("Content-Type").orElseThrow())
                .contains("text/event-stream");
        assertThat(toolsList.body()).contains("event: message").contains("tf_status");
    }

    @Test
    void exactPathIsEnforcedAndOversizedBodyIsRejectedBeforeForwarding() {
        startServer(controlConfig().withMaxRequestBytes(256),
                Map.of(TOKEN_ENV, TOKEN));

        HttpResponse<String> wrongPath = rawPost("/control/mcp/extra", "Bearer " + TOKEN,
                initializeBody(1));
        assertThat(wrongPath.statusCode()).isEqualTo(404);

        String oversized = initializeBody(2).replace("raw-test", "raw-test-" + "x".repeat(500));
        HttpResponse<String> tooLarge = rawPost(PATH, "Bearer " + TOKEN, oversized);
        assertThat(tooLarge.statusCode()).isEqualTo(413);
    }

    @Test
    void getListeningStreamIsProxiedAsSse() throws Exception {
        startServer(controlConfig(), Map.of(TOKEN_ENV, TOKEN));

        HttpResponse<String> init = rawInitialize(PATH, "Bearer " + TOKEN);
        assertThat(init.statusCode()).isEqualTo(200);
        String sessionId = init.headers().firstValue("Mcp-Session-Id").orElseThrow();

        HttpRequest get = HttpRequest.newBuilder(uri(PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .GET()
                .build();
        HttpResponse<InputStream> response = RAW_HTTP.send(get, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .contains("text/event-stream");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .contains("no-cache");
        }
    }

    @Test
    void nonLoopbackHostIsRejectedBeforeBind() {
        initDomain();
        ControlMcpServer rejected = ControlMcpServer.builder(
                        engine, eventReadPort, supervisor,
                        ControlMcpConfig.enabled("0.0.0.0", 0, PATH, TOKEN_ENV))
                .secretProvider(SecretProvider.of(Map.of(TOKEN_ENV, TOKEN)))
                .clock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                .build();

        assertThatThrownBy(rejected::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非回环")
                .satisfies(failure -> assertThat(allMessages(failure)).doesNotContain(TOKEN));
        assertThatThrownBy(rejected::boundPort).isInstanceOf(IllegalStateException.class);
        rejected.close();
    }

    @Test
    void tokenDoesNotAppearInToStringOrFailureText() {
        initDomain();
        ControlMcpServer instance = ControlMcpServer.builder(
                        engine, eventReadPort, supervisor,
                        controlConfig().withMaxRequestBytes(1024))
                .bearerToken(TOKEN)
                .clock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                .build();
        server = instance;

        assertThat(instance.toString())
                .doesNotContain(TOKEN)
                .contains("bearerConfigured=false");
        instance.start();
        assertThat(instance.toString())
                .doesNotContain(TOKEN)
                .contains("bearerConfigured=true");

        ControlMcpServer invalid = ControlMcpServer.builder(
                        engine, eventReadPort, supervisor,
                        ControlMcpConfig.enabled("0.0.0.0", 0, PATH, TOKEN_ENV))
                .bearerToken(INTERNAL_SECRET)
                .build();
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(invalid::start);
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(allMessages(failure))
                .doesNotContain(TOKEN)
                .doesNotContain(INTERNAL_SECRET);
        invalid.close();
    }

    @Test
    void missingBearerSecretFailsClosedAndCloseIsIdempotent() {
        initDomain();
        ControlMcpServer noToken = ControlMcpServer.builder(
                        engine, eventReadPort, supervisor,
                        ControlMcpConfig.enabled("127.0.0.1", 0, PATH, "MISSING_CONTROL_MCP_TOKEN"))
                .secretProvider(SecretProvider.of(Map.of()))
                .build();
        assertThatThrownBy(noToken::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
        assertThatThrownBy(noToken::boundPort).isInstanceOf(IllegalStateException.class);

        noToken.close();
        noToken.close(); // 幂等
        assertThatThrownBy(noToken::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    void explicitBearerTokenInjectionWorksEvenWithoutEnvName() {
        initDomain();
        ControlMcpServer injected = ControlMcpServer.builder(
                        engine, eventReadPort, supervisor,
                        new ControlMcpConfig(true, "127.0.0.1", 0, PATH, "", 1024))
                .bearerToken(TOKEN)
                .build();
        server = injected;
        injected.start();
        assertThat(injected.boundPort()).isPositive();
        assertThat(injected.toString()).doesNotContain(TOKEN).contains("bearerConfigured=true");
    }

    // ------------------------------------------------------------------
    // 装配与测试装置
    // ------------------------------------------------------------------

    private void startServer(ControlMcpConfig config, Map<String, String> secrets) {
        initDomain();
        server = ControlMcpServer.builder(engine, eventReadPort, supervisor, config)
                .secretProvider(SecretProvider.of(secrets))
                .clock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                .build();
        server.start();
    }

    private static ControlMcpConfig controlConfig() {
        return ControlMcpConfig.enabled("127.0.0.1", 0, PATH, TOKEN_ENV);
    }

    private void initDomain() {
        eventStore = new InMemoryEventStore();
        engine = new TaskflowEngine(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), eventStore);
        eventReadPort = new InMemoryEventReadPort(eventStore);
        supervisor = new Actor("supervisor-1", ActorRole.SUPERVISOR);
    }

    private McpSyncClient newClient(String authorizationHeader) {
        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.boundPort())
                        .endpoint(PATH)
                        .resumableStreams(false)
                        .connectTimeout(Duration.ofSeconds(5));
        if (authorizationHeader != null) {
            builder.httpRequestCustomizer((request, method, uri, body, context) ->
                    request.header("Authorization", authorizationHeader));
        }
        McpSyncClient client = McpClient.sync(builder.build()).build();
        clients.add(client);
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + path);
    }

    private HttpResponse<String> rawInitialize(String path, String authorization) {
        return rawPost(path, authorization, initializeBody(1));
    }

    private HttpResponse<String> rawPost(String path, String authorization, String body) {
        return rawPost(path, authorization, null, body);
    }

    private HttpResponse<String> rawSessionPost(
            String path, String authorization, String sessionId, String body) {
        return rawPost(path, authorization, sessionId, body);
    }

    private HttpResponse<String> rawPost(
            String path, String authorization, String sessionId, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        try {
            return RAW_HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new AssertionError("裸 HTTP 请求失败", failure);
        }
    }

    private static String initializeBody(int id) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"raw-test","version":"1.0"}
                }}
                """.formatted(id);
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
