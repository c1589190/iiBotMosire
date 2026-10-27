package io.mosire.iibot.app.inbox;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.inbox.client.InboxModels;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.inbox.InboxDigest;
import io.mosire.iibot.bot.summary.MessageSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
class BotInboxRuntimeTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void passesEmbeddedNetworkAndSweepParameters() throws Exception {
        BotInboxRuntimeOptions options = new BotInboxRuntimeOptions(
                tempDir.resolve("custom-inbox"), "bot-custom", "dsh-custom",
                Set.of("qq"), 1L,
                "127.0.0.1", 0, "127.0.0.1", 0, "/custom/mcp",
                Duration.ofMillis(2_500), Duration.ofMillis(250));
        try (BotInboxRuntime runtime = BotInboxRuntime.start(options)) {
            assertThat(runtime.serverConfig().mcpPath()).isEqualTo("/custom/mcp");
            assertThat(runtime.serverConfig().apiPort()).isZero();
            assertThat(runtime.serverConfig().mcpPort()).isZero();
            assertThat(runtime.serverConfig().sseHeartbeat())
                    .isEqualTo(Duration.ofMillis(2_500));
            assertThat(runtime.claimSweepInterval()).isEqualTo(Duration.ofMillis(250));
            assertThat(runtime.apiPort()).isPositive();
            assertThat(runtime.mcpPort()).isPositive();
            assertThat(runtime.descriptor().apiBaseUrl())
                    .contains(":" + runtime.apiPort());
        }
    }

    @Test
    void startsGenericInboxWithoutGameServicesAndExposesDigestPort() throws Exception {
        try (BotInboxRuntime runtime = BotInboxRuntime.start(
                tempDir.resolve("inbox"), "bot-inbox", "dsh-session-bot",
                Set.of("qq"), 1L)) {
            assertThat(runtime.mcpTools()).extracting(AgentTool::name)
                    .contains("inbox_status", "inbox_rest", "inbox_view", "inbox_update",
                            "inbox_append", "inbox_delete",
                            InboxBindingDescriptorTool.NAME)
                    .hasSize(17);
            assertThat(Files.getPosixFilePermissions(runtime.programTokenPath()))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
            assertThat(runtime.descriptor().adapterTokenRef())
                    .isEqualTo("file:" + runtime.wakeTokenPath().toAbsolutePath().normalize());

            InboxDigest digest = InboxDigest.of(List.of(new MessageSummary(
                    "thread:test", 1L, "m-1", ChatDirection.IN, "qq-1", "Alice",
                    Set.of("vip"), "", 80, "Alice：你好", T0)));
            String itemId = runtime.digestPort()
                    .push("qq-thread:test:active", digest).itemId();
            InboxModels.ItemList list = runtime.programClient()
                    .list("bot-inbox", Set.of("qq"), 10);
            assertThat(list.items()).extracting(InboxModels.ItemSummary::itemId)
                    .containsExactly(itemId);
        }
    }
}
