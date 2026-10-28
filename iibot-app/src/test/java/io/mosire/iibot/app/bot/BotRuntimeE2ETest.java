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
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

            JsonNode read = call(client, "qq_conversation_read",
                    Map.of("threadId", threadId, "limit", 10));
            assertThat(read.path("data").path("messages").get(0).path("text").asText())
                    .isEqualTo("你好");
            assertThat(read.path("data").path("deliveredSeq").asLong()).isPositive();
            // read 只预览，Inbox 未读仍保留；显式 ack 后才归档。
            assertThat(runtime.inboxRuntime().programClient()
                    .list("bot-inbox", java.util.Set.of("qq"), 10).items()).hasSize(1);
            JsonNode ack = call(client, "qq_conversation_ack",
                    Map.of("threadId", threadId));
            assertThat(ack.path("data").path("acked").asBoolean()).isTrue();
            assertThat(ack.path("data").path("inboxArchived").asBoolean()).isTrue();
            assertThat(runtime.inboxRuntime().programClient()
                    .list("bot-inbox", java.util.Set.of("qq"), 10).items()).isEmpty();

            // 完整历史读取不受已读/ack 状态影响。
            JsonNode history = call(client, "bot_thread_read",
                    Map.of("threadId", threadId, "limit", 10));
            assertThat(history.path("data").path("messages").get(0).path("text").asText())
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


    @Test
    void twoAdaptersRouteInboundThreadsAndOutboundRepliesByAccountAndCloseAll() throws Exception {
        FakeMessagingAdapter adapterA = new FakeMessagingAdapter("adapter-a");
        FakeMessagingAdapter adapterB = new FakeMessagingAdapter("adapter-b");
        AccountRegistry registry = new AccountRegistry();
        registry.register("bot-a", adapterA);
        registry.register("bot-b", adapterB);
        BotInboxRuntimeOptions inboxOptions = new BotInboxRuntimeOptions(
                tempDir.resolve("inbox-multi"), "bot-inbox-multi", "dsh-session-bot",
                java.util.Set.of("qq"), 1L,
                "127.0.0.1", 0, "127.0.0.1", 0, "/multi/mcp",
                Duration.ofMillis(2_500), Duration.ofMillis(250));
        BotRuntimeOptions options = new BotRuntimeOptions(
                tempDir.resolve("data-multi"), "bot-a", "127.0.0.1", 0, CONTROL_PATH,
                TOKEN, inboxOptions, 20L, 100, Duration.ofSeconds(2), null,
                Duration.ZERO, WeightSettings.defaults(),
                List.of(
                        new BotRuntimeOptions.AccountOptions("alias-a", "bot-a", "temp-a"),
                        new BotRuntimeOptions.AccountOptions("alias-b", "bot-b", "temp-b")));
        BotRuntime runtime = BotRuntime.start(registry, options);
        McpSyncClient client = null;
        try {
            adapterA.deliver(new InboundMessage("adapter-a", "bot-a", "ma-1", "qq-a",
                    "Alice", InboundMessage.SessionType.GROUP, "group-a", "来自 A", "", "",
                    false, Instant.parse("2026-10-28T12:00:00Z"), "dedupe-ma-1"));
            adapterB.deliver(new InboundMessage("adapter-b", "bot-b", "mb-1", "qq-b",
                    "Bob", InboundMessage.SessionType.PRIVATE, null, "来自 B", "", "",
                    false, Instant.parse("2026-10-28T12:00:01Z"), "dedupe-mb-1"));

            assertThat(runtime.board().threads(10))
                    .extracting(thread -> thread.source().accountId())
                    .containsExactlyInAnyOrder("bot-a", "bot-b");

            client = mcpClient(runtime);
            JsonNode threads = call(client, "bot_thread_list", Map.of("limit", 10));
            String threadA = threadIdForAccount(threads, "bot-a");
            String threadB = threadIdForAccount(threads, "bot-b");

            JsonNode replyA = call(client, "bot_reply", Map.of(
                    "threadId", threadA, "text", "回复 A",
                    "idempotencyKey", "multi-reply-a"));
            JsonNode replyB = call(client, "bot_reply", Map.of(
                    "threadId", threadB, "text", "回复 B",
                    "idempotencyKey", "multi-reply-b"));
            assertThat(replyA.path("data").path("deliveryState").asText())
                    .isEqualTo("SENT");
            assertThat(replyB.path("data").path("deliveryState").asText())
                    .isEqualTo("SENT");
            assertThat(adapterA.sent()).hasSize(1);
            assertThat(adapterA.sent().getFirst().textSegments()).containsExactly("回复 A");
            assertThat(adapterB.sent()).hasSize(1);
            assertThat(adapterB.sent().getFirst().textSegments()).containsExactly("回复 B");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
            runtime.close();
        }
        assertThat(adapterA.isClosed()).isTrue();
        assertThat(adapterB.isClosed()).isTrue();
    }


    @Test
    void startupFailureRollsBackAndClosesAdapters() {
        FakeMessagingAdapter adapterA = new FakeMessagingAdapter("adapter-a");
        FakeMessagingAdapter adapterB = new FakeMessagingAdapter("adapter-b", true);
        AccountRegistry registry = new AccountRegistry();
        registry.register("bot-a", adapterA);
        registry.register("bot-b", adapterB);
        BotInboxRuntimeOptions inboxOptions = new BotInboxRuntimeOptions(
                tempDir.resolve("inbox-rollback"), "bot-inbox-rollback", "dsh-session-bot",
                java.util.Set.of("qq"), 1L,
                "127.0.0.1", 0, "127.0.0.1", 0, "/rollback/mcp",
                Duration.ofMillis(2_500), Duration.ofMillis(250));
        BotRuntimeOptions options = new BotRuntimeOptions(
                tempDir.resolve("data-rollback"), "bot-a", "127.0.0.1", 0, CONTROL_PATH,
                TOKEN, inboxOptions, 20L, 100, Duration.ofSeconds(2), null,
                Duration.ZERO, WeightSettings.defaults(),
                List.of(
                        new BotRuntimeOptions.AccountOptions("alias-a", "bot-a", null),
                        new BotRuntimeOptions.AccountOptions("alias-b", "bot-b", null)));

        assertThatThrownBy(() -> BotRuntime.start(registry, options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟启动失败");
        assertThat(adapterA.isClosed()).isTrue();
        assertThat(adapterB.isClosed()).isTrue();
    }


    @Test
    void loginAccountMismatchFailsClosedAndClosesAdapter() {
        FakeMessagingAdapter delegate = new FakeMessagingAdapter("adapter-a");
        MessagingAdapter mismatched = new AccountInfoFakeAdapter(
                delegate, new AccountInfo("bot-b", "wrong-account"));
        BotInboxRuntimeOptions inboxOptions = new BotInboxRuntimeOptions(
                tempDir.resolve("inbox-identity"), "bot-inbox-identity", "dsh-session-bot",
                java.util.Set.of("qq"), 1L,
                "127.0.0.1", 0, "127.0.0.1", 0, "/identity/mcp",
                Duration.ofMillis(2_500), Duration.ofMillis(250));
        BotRuntimeOptions options = new BotRuntimeOptions(
                tempDir.resolve("data-identity"), "bot-a", "127.0.0.1", 0, CONTROL_PATH,
                TOKEN, inboxOptions, 20L, 100, Duration.ofSeconds(2), null,
                Duration.ZERO, WeightSettings.defaults());

        assertThatThrownBy(() -> BotRuntime.start(mismatched, options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accountId");
        assertThat(delegate.isClosed()).isTrue();
    }

    private static String threadIdForAccount(JsonNode threads, String accountId) {
        for (JsonNode thread : threads.path("data").path("threads")) {
            if (accountId.equals(thread.path("accountId").asText())) {
                return thread.path("threadId").asText();
            }
        }
        throw new AssertionError("未找到 accountId=" + accountId + " 的 thread");
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
            call(client, "qq_conversation_read", Map.of("threadId", threadId));
            call(client, "qq_conversation_ack", Map.of("threadId", threadId));

            adapter.deliver(groupMessage("m-ambient", "普通群聊", false));
            String ambientItem = awaitInboxItem(runtime);
            var ambientDetail = runtime.inboxRuntime().programClient()
                    .view("bot-inbox", ambientItem);
            JsonNode ambientContent = (JsonNode) ambientDetail.content();
            assertThat(ambientContent.path("wakeReason").asText())
                    .isEqualTo("BELOW_THRESHOLD");
            assertThat(ambientContent.path("priority").asInt()).isZero();
            call(client, "qq_conversation_read", Map.of("threadId", threadId));
            call(client, "qq_conversation_ack", Map.of("threadId", threadId));

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
    private static final class AccountInfoFakeAdapter
            implements MessagingAdapter, AccountInfoProvider {
        private final FakeMessagingAdapter delegate;
        private final AccountInfo info;

        private AccountInfoFakeAdapter(FakeMessagingAdapter delegate, AccountInfo info) {
            this.delegate = delegate;
            this.info = info;
        }

        @Override public String adapterId() { return delegate.adapterId(); }
        @Override public void start(InboundHandler handler) { delegate.start(handler); }
        @Override public CompletionStage<SendReceipt> send(OutboundMessage message) {
            return delegate.send(message);
        }
        @Override public AdapterStatus status() { return delegate.status(); }
        @Override public void addEventListener(AdapterEventListener listener) {
            delegate.addEventListener(listener);
        }
        @Override public void removeEventListener(AdapterEventListener listener) {
            delegate.removeEventListener(listener);
        }
        @Override public void close() { delegate.close(); }
        @Override public CompletionStage<AccountInfo> accountInfo() {
            return CompletableFuture.completedFuture(info);
        }
    }

}
