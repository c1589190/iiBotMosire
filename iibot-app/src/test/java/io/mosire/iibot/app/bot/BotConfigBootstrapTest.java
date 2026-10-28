package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.SecretProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BotConfigBootstrapTest {

    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_CONTROL_TOKEN", "control-secret-value",
            "IIBOT_NAPCAT_ACCESS_TOKEN", "napcat-secret-value"));

    @TempDir
    Path tempDir;

    @Test
    void generatesSingleCompleteTemplateWith0600AndDoesNotOverwriteIt() throws Exception {
        Path configDir = tempDir.resolve("config/bot");

        BotConfigBootstrap.Result first = BotConfigBootstrap.ensureDefault(configDir);

        assertThat(first.generated()).isTrue();
        assertThat(first.files()).containsExactly(configDir.resolve("bot.json"));
        assertThat(configDir.resolve("bot.json")).isRegularFile();
        assertThat(configDir.resolve("00-base.json")).doesNotExist();
        assertThat(configDir.resolve("10-napcat.json")).doesNotExist();
        assertThat(Files.getPosixFilePermissions(configDir))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
        assertThat(Files.getPosixFilePermissions(configDir.resolve("bot.json")))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);

        String template = Files.readString(configDir.resolve("bot.json"));
        assertThat(template)
                .contains("\"accounts\"")
                .contains("\"oneBot\"")
                .contains("\"messageBoard\"")
                .contains("\"logging\"")
                .contains("IIBOT_NAPCAT_ACCESS_TOKEN")
                .contains("IIBOT_CONTROL_TOKEN")
                .doesNotContain("control-secret-value")
                .doesNotContain("napcat-secret-value");

        byte[] original = Files.readAllBytes(configDir.resolve("bot.json"));
        BotConfigBootstrap.Result second = BotConfigBootstrap.ensureDefault(configDir);
        assertThat(second.generated()).isFalse();
        assertThat(second.files()).isEmpty();
        assertThat(Files.readAllBytes(configDir.resolve("bot.json"))).isEqualTo(original);

        BotConfig config = BotConfigLoader.load(configDir, SECRETS);
        assertThat(config.activeAccount().accountId()).isEqualTo("REPLACE_WITH_BOT_QQ");
        assertThat(config.dataDir()).isEqualTo(tempDir.resolve("data").toAbsolutePath());
        assertThat(config.logging().directory())
                .isEqualTo(tempDir.resolve("logs").toAbsolutePath());
        assertThat(config.inbox().apiPort()).isZero();
        assertThat(config.inbox().mcpPath()).isEqualTo("/mcp");
        assertThat(config.inbox().heartbeatMillis()).isEqualTo(15_000L);
        assertThat(config.inbox().claimSweepIntervalMillis()).isEqualTo(5_000L);
    }

    @Test
    void existingJsonPreventsBootstrap() throws Exception {
        Path configDir = tempDir.resolve("config/bot");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("existing.json"), "{\"custom\":true}");

        BotConfigBootstrap.Result result = BotConfigBootstrap.ensureDefault(configDir);

        assertThat(result.generated()).isFalse();
        assertThat(result.files()).isEmpty();
        assertThat(configDir.resolve("bot.json")).doesNotExist();
    }

    @Test
    void directoryPermissionsAreOwnerOnly() throws Exception {
        Path configDir = tempDir.resolve("config/bot");
        Path file = BotConfigBootstrap.ensureDefault(configDir).files().getFirst();

        Set<PosixFilePermission> filePermissions = Files.getPosixFilePermissions(file);
        assertThat(filePermissions).doesNotContain(PosixFilePermission.GROUP_READ);
        assertThat(filePermissions).doesNotContain(PosixFilePermission.OTHERS_READ);
    }
}
