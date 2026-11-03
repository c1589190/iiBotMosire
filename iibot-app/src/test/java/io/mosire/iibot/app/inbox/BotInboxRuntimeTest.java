package io.mosire.iibot.app.inbox;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.client.InboxModels;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.inbox.InboxDigest;
import io.mosire.iibot.bot.summary.MessageSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
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
    void restartsReuseTheTokenFilesInsteadOfReissuing() throws Exception {
        Path dataDir = tempDir.resolve("inbox-reuse");
        String program;
        String wake;
        String descriptorRef;
        try (BotInboxRuntime runtime = BotInboxRuntime.start(
                dataDir, "bot-reuse", "dsh-reuse", Set.of("qq"), 0L)) {
            program = Files.readString(runtime.programTokenPath());
            wake = Files.readString(runtime.wakeTokenPath());
            descriptorRef = runtime.descriptor().adapterTokenRef();
        }
        try (BotInboxRuntime runtime = BotInboxRuntime.start(
                dataDir, "bot-reuse", "dsh-reuse", Set.of("qq"), 0L)) {
            // 文件即配置：重启后 token 不变，Hermes 侧不会拿到死值
            assertThat(Files.readString(runtime.programTokenPath())).isEqualTo(program);
            assertThat(Files.readString(runtime.wakeTokenPath())).isEqualTo(wake);
            assertThat(runtime.descriptor().adapterTokenRef()).isEqualTo(descriptorRef);
            // 复用来的凭据仍然可用
            assertThat(runtime.programClient().whoAmI().inbox()).isEqualTo("bot-reuse");
        }
    }

    @Test
    void fixedTokensFromConfigAreUsedVerbatimAndSurviveRestarts() throws Exception {
        Path dataDir = tempDir.resolve("inbox-fixed");
        String program = "inbox_fixed_program_token_0001";
        String wake = "inbox_fixed_wake_token_0000001";
        BotInboxRuntimeOptions options = new BotInboxRuntimeOptions(
                dataDir, "bot-fixed", "dsh-fixed", Set.of("qq"), 0L,
                "127.0.0.1", 0, "127.0.0.1", 0, "/mcp",
                Duration.ofSeconds(15), Duration.ofSeconds(5), program, wake);

        try (BotInboxRuntime runtime = BotInboxRuntime.start(options)) {
            assertThat(Files.readString(runtime.programTokenPath())).isEqualTo(program);
            assertThat(Files.readString(runtime.wakeTokenPath())).isEqualTo(wake);
            // 固定 token 被登记成不过期凭据，且真的能用
            assertThat(runtime.programClient().whoAmI().expiresAt()).isNull();
            assertThat(wakeClient(runtime).whoAmI().inbox()).isEqualTo("bot-fixed");
        }
        // 再启动一次：token 值仍然是我们给的那个（不是服务端重新生成的）
        try (BotInboxRuntime runtime = BotInboxRuntime.start(options)) {
            assertThat(Files.readString(runtime.programTokenPath())).isEqualTo(program);
            assertThat(Files.readString(runtime.wakeTokenPath())).isEqualTo(wake);
        }
    }

    @Test
    void zeroTtlMeansAPermanentWakeCredential() throws Exception {
        try (BotInboxRuntime runtime = BotInboxRuntime.start(
                tempDir.resolve("inbox-perm"), "bot-perm", "dsh-perm", Set.of("qq"), 0L)) {
            assertThat(runtime.programClient().whoAmI().expiresAt()).isNull();
            assertThat(wakeClient(runtime).whoAmI().expiresAt()).isNull();
        }
    }

    @Test
    void positiveTtlStillExpiresTheWakeCredential() throws Exception {
        try (BotInboxRuntime runtime = BotInboxRuntime.start(
                tempDir.resolve("inbox-ttl"), "bot-ttl", "dsh-ttl", Set.of("qq"), 24L)) {
            assertThat(wakeClient(runtime).whoAmI().expiresAt()).isNotBlank();
        }
    }

    private static InboxClient wakeClient(BotInboxRuntime runtime) {
        return InboxClient.forTokenFile(
                URI.create(runtime.descriptor().apiBaseUrl()), runtime.wakeTokenPath());
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
