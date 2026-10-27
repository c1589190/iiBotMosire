package io.mosire.iibot.app.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BotConfigBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesLoadableTemplateAndDoesNotOverwriteIt() throws Exception {
        Path configDir = tempDir.resolve("config/bot");

        BotConfigBootstrap.Result first = BotConfigBootstrap.ensureDefault(configDir);

        assertThat(first.generated()).isTrue();
        assertThat(first.files()).hasSize(4);
        assertThat(first.files()).allSatisfy(path -> assertThat(path).isRegularFile());
        byte[] original = Files.readAllBytes(configDir.resolve("00-base.json"));

        BotConfigBootstrap.Result second = BotConfigBootstrap.ensureDefault(configDir);
        assertThat(second.generated()).isFalse();
        assertThat(second.files()).isEmpty();
        assertThat(Files.readAllBytes(configDir.resolve("00-base.json")))
                .isEqualTo(original);

        BotConfig config = BotConfigLoader.load(configDir);
        assertThat(config.accountId()).isEqualTo("REPLACE_WITH_BOT_QQ");
        assertThat(config.dataDir()).isEqualTo(tempDir.resolve("data").toAbsolutePath());
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
        assertThat(configDir.resolve("00-base.json")).doesNotExist();
    }
}
