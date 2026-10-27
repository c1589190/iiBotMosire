package io.mosire.iibot.bot.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class BotMcpServerTest {

    private static final String PATH = "/bot/mcp";
    private static final String TOKEN = "bot-test-token-9e2c";

    @Test
    void exposesRegisteredToolsThroughLoopbackBearerMcp() {
        BotMcpServer server = new BotMcpServer("127.0.0.1", 0, PATH, TOKEN,
                "iibot-bot-test", "0.1.0", List.of(new EchoTool()));
        McpSyncClient client = null;
        try {
            server.start();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + server.boundPort())
                    .endpoint(PATH)
                    .resumableStreams(false)
                    .connectTimeout(Duration.ofSeconds(5))
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                            request.header("Authorization", "Bearer " + TOKEN))
                    .build();
            client = McpClient.sync(transport).build();
            client.initialize();

            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactly("bot_echo");
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("bot_echo", Map.of("text", "ping")));
            assertThat(result.isError()).isFalse();
            assertThat(wireText(result)).contains("ping");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            server.close();
        }
    }

    @Test
    void emitsImageContentWhenToolReturnsAssetId() {
        ToolAssetResolver resolver = id -> "img-1".equals(id)
                ? java.util.Optional.of(new ToolAsset("image/png", new byte[]{1, 2, 3}))
                : java.util.Optional.empty();
        BotMcpServer server = new BotMcpServer("127.0.0.1", 0, PATH, TOKEN,
                "iibot-bot-test", "0.1.0", List.of(new AssetTool()), resolver);
        McpSyncClient client = null;
        try {
            server.start();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + server.boundPort())
                    .endpoint(PATH)
                    .resumableStreams(false)
                    .connectTimeout(Duration.ofSeconds(5))
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                            request.header("Authorization", "Bearer " + TOKEN))
                    .build();
            client = McpClient.sync(transport).build();
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("bot_asset", Map.of()));
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).anySatisfy(content -> {
                assertThat(content).isInstanceOf(McpSchema.ImageContent.class);
                McpSchema.ImageContent image = (McpSchema.ImageContent) content;
                assertThat(image.mimeType()).isEqualTo("image/png");
                assertThat(image.data()).isEqualTo("AQID");
            });
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            server.close();
        }
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static final class EchoTool implements AgentTool {
        @Override public String name() { return "bot_echo"; }
        @Override public String description() { return "echo"; }
        @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }
        @Override public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "text", Map.of("type", "string")), "required", List.of("text"));
        }
        @Override public ToolResult execute(ToolContext context) {
            return ToolResult.ok(String.valueOf(context.arguments().get("text")));
        }
    }
    private static final class AssetTool implements AgentTool {
        @Override public String name() { return "bot_asset"; }
        @Override public String description() { return "asset"; }
        @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }
        @Override public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }
        @Override public ToolResult execute(ToolContext context) {
            return ToolResult.ok("图片", List.of("img-1"));
        }
    }

}
