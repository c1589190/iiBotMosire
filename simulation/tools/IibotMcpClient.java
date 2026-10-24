import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/** 本地 MCP smoke 客户端；token 只从环境变量读取。 */
public final class IibotMcpClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: IibotMcpClient <tool-name|--list> [json-args-file-or-string]");
            System.exit(2);
        }
        String token = System.getenv("IIBOT_CONTROL_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("IIBOT_CONTROL_TOKEN missing");
        }
        String host = System.getenv().getOrDefault("IIBOT_MCP_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("IIBOT_MCP_PORT", "8765"));
        String path = System.getenv().getOrDefault("IIBOT_MCP_PATH", "/mcp/control");
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://" + host + ":" + port)
                        .endpoint(path)
                        .resumableStreams(false)
                        .connectTimeout(Duration.ofSeconds(5))
                        .httpRequestCustomizer((builder, method, uri, body, context) ->
                                builder.header("Authorization", "Bearer " + token))
                        .build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMinutes(5))
                .initializationTimeout(Duration.ofSeconds(20))
                .build()) {
            McpSchema.InitializeResult init = client.initialize();
            System.err.println("MCP_INIT=" + init.serverInfo().name() + " "
                    + init.serverInfo().version());
            if ("--list".equals(args[0])) {
                McpSchema.ListToolsResult list = client.listTools();
                for (McpSchema.Tool tool : list.tools()) {
                    System.out.println(tool.name() + "\t" + tool.description());
                }
                return;
            }
            Map<String, Object> arguments = args.length >= 2
                    ? JSON.readValue(readArg(args[1]),
                            new TypeReference<Map<String, Object>>() {})
                    : Map.of();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(args[0], arguments));
            System.out.println("IS_ERROR=" + result.isError());
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    System.out.println(text.text());
                } else {
                    System.out.println(content);
                }
            }
        }
    }

    private static String readArg(String arg) throws Exception {
        if (arg.startsWith("@")) {
            return java.nio.file.Files.readString(java.nio.file.Path.of(arg.substring(1)));
        }
        if (java.nio.file.Files.isRegularFile(java.nio.file.Path.of(arg))) {
            return java.nio.file.Files.readString(java.nio.file.Path.of(arg));
        }
        return arg;
    }
}
