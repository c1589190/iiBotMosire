package io.mosire.iibot.app.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.iibot.app.inbox.BotInboxRuntimeOptions;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.bot.weight.WeightSettings;
import io.mosire.iibot.connect.core.InboundMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
class BotRuntimeE2ETest {

    private static final String TOKEN = "bot-runtime-token-6d1f";
    private static final String CONTROL_PATH = "/bot/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void ingestToInboxToMcpReadAndReplyWithoutGameServices() throws Exception {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        BotInboxRuntimeOptions inboxOptions = new BotInboxRuntimeOptions(
                tempDir.resolve("inbox"), "bot-inbox", "dsh-session-bot",
                java.util.Set.of("qq"), 1L,
                "127.0.0.1", 0, "127.0.0.1", 0, "/custom/mcp",
                Duration.ofMillis(2_500), Duration.ofMillis(250));
        BotRuntimeOptions options = new BotRuntimeOptions(
                tempDir.resolve("data"), "bot-1", "127.0.0.1", 0, CONTROL_PATH, TOKEN,
                inboxOptions, 20L, 100, Duration.ofSeconds(2), null, Duration.ZERO,
                WeightSettings.defaults());
        BotRuntime runtime = BotRuntime.start(adapter, options);
        McpSyncClient client = null;
        try {
            assertThat(runtime.inboxRuntime().serverConfig().mcpPath())
                    .isEqualTo("/custom/mcp");
            assertThat(runtime.inboxRuntime().claimSweepInterval())
                    .isEqualTo(Duration.ofMillis(250));
            adapter.deliver(new InboundMessage("adapter-1", "bot-1", "m-1", "qq-1",
                    "Alice", InboundMessage.SessionType.GROUP, "group-9", "你好",
                    "[图片×1]", "", false, Instant.parse("2026-10-26T12:00:00Z"),
                    "dedupe-1"));

            String itemId = awaitInboxItem(runtime);
            assertThat(itemId).startsWith("itm_");

            client = mcpClient(runtime);
            JsonNode threads = call(client, "bot_thread_list", Map.of());
            assertThat(threads.path("data").path("threads")).hasSize(1);
            String threadId = threads.path("data").path("threads").get(0)
                    .path("threadId").asText();

            JsonNode read = call(client, "bot_thread_read",
                    Map.of("threadId", threadId, "limit", 10));
            assertThat(read.path("data").path("messages").get(0).path("text").asText())
                    .isEqualTo("你好");

            JsonNode reply = call(client, "bot_reply", Map.of(
                    "threadId", threadId,
                    "text", "收到",
                    "idempotencyKey", "reply-e2e-1"));
            assertThat(reply.path("data").path("deliveryState").asText()).isEqualTo("SENT");
            assertThat(adapter.sent()).hasSize(1);
            assertThat(adapter.sent().getFirst().textSegments()).containsExactly("收到");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    private static String awaitInboxItem(BotRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var list = runtime.inboxRuntime().programClient()
                    .list("bot-inbox", java.util.Set.of("qq"), 10);
            if (!list.items().isEmpty()) {
                return list.items().getFirst().itemId();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Inbox digest 未在时限内出现");
    }

    @Test
    void sourceWeightsSuppressAmbientAndWakeOnMention() throws Exception {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        WeightSettings defaults = WeightSettings.defaults();
        WeightSettings fast = new WeightSettings(
                defaults.defaultPrivateSourceWeight(), defaults.defaultGroupSourceWeight(),
                defaults.defaultNotifyThreshold(), defaults.signalWeights(),
                defaults.defaultKeywordWeights(), defaults.aliases(),
                defaults.commandPrefixes(), defaults.maxMessagesPerDigest(),
                20L, 0L);
        BotRuntimeOptions options = new BotRuntimeOptions(
                tempDir.resolve("data"), tempDir.resolve("inbox"),
                "bot-1", "127.0.0.1", 0, CONTROL_PATH, TOKEN,
                "bot-inbox", "dsh-session-bot", java.util.Set.of("qq"), 1L,
                20L, 100, Duration.ofSeconds(2), null, Duration.ZERO, fast);
        BotRuntime runtime = BotRuntime.start(adapter, options);
        McpSyncClient client = null;
        try {
            adapter.deliver(groupMessage("m-first", "普通群聊", false));
            String firstItem = awaitInboxItem(runtime);
            var firstDetail = runtime.inboxRuntime().programClient()
                    .view("bot-inbox", firstItem);
            JsonNode firstContent = (JsonNode) firstDetail.content();
            String threadId = firstContent.path("threadId").asText();
            assertThat(firstContent.path("wakeReason").asText()).isEqualTo("FIRST_CONTACT");
            assertThat(firstContent.path("priority").asInt()).isEqualTo(100);

            client = mcpClient(runtime);
            call(client, "bot_thread_ack", Map.of(
                    "threadId", threadId,
                    "itemId", firstItem,
                    "expectedRevision", firstDetail.revision(),
                    "snapshotToSeq", firstContent.path("toSeq").asLong()));

            adapter.deliver(groupMessage("m-ambient", "普通群聊", false));
            String ambientItem = awaitInboxItem(runtime);
            var ambientDetail = runtime.inboxRuntime().programClient()
                    .view("bot-inbox", ambientItem);
            JsonNode ambientContent = (JsonNode) ambientDetail.content();
            assertThat(ambientContent.path("wakeReason").asText())
                    .isEqualTo("BELOW_THRESHOLD");
            assertThat(ambientContent.path("priority").asInt()).isZero();
            call(client, "bot_thread_ack", Map.of(
                    "threadId", threadId,
                    "itemId", ambientItem,
                    "expectedRevision", ambientDetail.revision(),
                    "snapshotToSeq", ambientContent.path("toSeq").asLong()));

            adapter.deliver(groupMessage("m-mention", "你看这个", true));
            String mentionItem = awaitInboxItem(runtime);
            var mentionDetail = runtime.inboxRuntime().programClient()
                    .view("bot-inbox", mentionItem);
            JsonNode mentionContent = (JsonNode) mentionDetail.content();
            assertThat(mentionContent.path("wakeReason").asText()).isEqualTo("MENTION");
            assertThat(mentionContent.path("weight").path("totalScore").asInt())
                    .isEqualTo(200);
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    private static InboundMessage groupMessage(String id, String text, boolean mentioned) {
        return new InboundMessage("adapter-1", "bot-1", id, "qq-1", "Alice",
                InboundMessage.SessionType.GROUP, "group-9", text, "", "",
                mentioned, Instant.parse("2026-10-26T12:00:00Z"), "dedupe-" + id,
                List.of());
    }

    private static McpSyncClient mcpClient(BotRuntime runtime) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + runtime.mcpServer().boundPort())
                .endpoint(CONTROL_PATH)
                .resumableStreams(false)
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private static JsonNode call(McpSyncClient client, String name, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(name, args));
        assertThat(result.isError()).as(name + " failed: " + wireText(result)).isFalse();
        return JSON.readTree(wireText(result));
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
