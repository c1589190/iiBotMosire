package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.JsonConfigBundle;
import io.mosire.iibot.app.config.SecretProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotConfigLoaderMultiFileTest {

    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_CONTROL_TOKEN", "control-secret-value",
            "IIBOT_NAPCAT_ACCESS_TOKEN", "napcat-secret-value"));

    @TempDir
    Path tempDir;

    @Test
    void deepMergesMultipleFilesAndResolvesAgainstConfigDirectory() throws Exception {
        Files.writeString(tempDir.resolve("00-base.json"), """
                {
                  "dataDir": "./data",
                  "accounts": {
                    "main": {
                      "enabled": true,
                      "adapter": "onebot11",
                      "accountId": "10001"
                    }
                  },
                  "control": {
                    "host": "127.0.0.1",
                    "port": 8765,
                    "path": "/mcp/bot",
                    "bearerTokenEnv": "IIBOT_CONTROL_TOKEN"
                  },
                  "inbox": {"inboxId": "iibot-bot", "channels": ["qq"]},
                  "messageBoard": {
                    "summaryDebounceMillis": 1000,
                    "maxPreviewMessages": 10
                  }
                }
                """);
        Files.writeString(tempDir.resolve("10-onebot.json"), """
                {
                  "accounts": {
                    "main": {
                      "oneBot": {
                        "eventWebSocketUri": "ws://127.0.0.1:3001/",
                        "httpBaseUri": "http://127.0.0.1:3000/",
                        "accessTokenEnv": "IIBOT_NAPCAT_ACCESS_TOKEN"
                      }
                    }
                  }
                }
                """);
        Files.writeString(tempDir.resolve("20-local.json"), """
                {
                  "control": {"port": 9999},
                  "inbox": {"channels": ["qq", "test"], "apiPort": 19001,
                            "mcpPath": "/custom/mcp"},
                  "messageBoard": {
                    "summaryDebounceMillis": 3000,
                    "maxPreviewMessages": 12,
                    "weighting": {
                      "signalWeights": {"MENTION": 220},
                      "keywordWeights": {"上线": 120}
                    }
                  }
                }
                """);

        BotConfig loaded = BotConfigLoader.load(List.of(tempDir), SECRETS);

        assertThat(loaded.dataDir()).isEqualTo(tempDir.resolve("data").toAbsolutePath());
        assertThat(loaded.control().port()).isEqualTo(9999);
        assertThat(loaded.inbox().channels()).containsExactlyInAnyOrder("qq", "test");
        assertThat(loaded.inbox().apiPort()).isEqualTo(19001);
        assertThat(loaded.inbox().mcpPath()).isEqualTo("/custom/mcp");
        assertThat(loaded.messageBoard().summaryDebounceMillis()).isEqualTo(3000L);
        assertThat(loaded.messageBoard().maxPreviewMessages()).isEqualTo(12);
        assertThat(loaded.messageBoard().weighting().toSettings(12).signalWeights()
                .get(io.mosire.iibot.bot.weight.WeightSignal.MENTION)).isEqualTo(220);
        assertThat(loaded.messageBoard().weighting().toSettings(12)
                .defaultKeywordWeights()).containsEntry("上线", 120);

        JsonConfigBundle.Bundle bundle = JsonConfigBundle.load(List.of(tempDir));
        assertThat(bundle.originOf("/control/port").orElseThrow().file().getFileName().toString())
                .isEqualTo("20-local.json");
        assertThat(bundle.originOf("/accounts/main/accountId").orElseThrow()
                .file().getFileName().toString()).isEqualTo("00-base.json");
        assertThat(bundle.originOf("/accounts/main/oneBot/httpBaseUri").orElseThrow()
                .file().getFileName().toString()).isEqualTo("10-onebot.json");
        assertThat(bundle.originOf("/messageBoard/maxPreviewMessages").orElseThrow()
                .file().getFileName().toString()).isEqualTo("20-local.json");
    }
}
