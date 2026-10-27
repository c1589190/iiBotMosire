package io.mosire.iibot.app.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotConfigLoaderMultiFileTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesMultipleConfigFilesAndResolvesPathsAgainstConfigDirectory() throws Exception {
        Files.writeString(tempDir.resolve("00-base.json"), """
                {
                  "dataDir": "./data",
                  "accountId": "10001",
                  "control": {"bearerTokenEnv": "IIBOT_CONTROL_TOKEN"},
                  "inbox": {"inboxId": "iibot-bot", "channels": ["qq"]},
                  "summary": {"debounceMillis": 1000}
                }
                """);
        Files.writeString(tempDir.resolve("10-napcat.json"), """
                {
                  "napcat": {
                    "eventWebSocketUri": "ws://127.0.0.1:3001/",
                    "httpBaseUri": "http://127.0.0.1:3000/"
                  }
                }
                """);
        Files.writeString(tempDir.resolve("20-local.json"), """
                {
                  "control": {"port": 9999},
                  "inbox": {"channels": ["qq", "test"], "apiPort": 19001,
                            "mcpPath": "/custom/mcp"},
                  "summary": {"debounceMillis": 3000, "maxMessagesPerDigest": 12},
                  "weighting": {
                    "signalWeights": {"MENTION": 220},
                    "keywordWeights": {"上线": 120}
                  }
                }
                """);

        BotConfig loaded = BotConfigLoader.load(List.of(tempDir));

        assertThat(loaded.dataDir()).isEqualTo(tempDir.resolve("data").toAbsolutePath());
        assertThat(loaded.control().port()).isEqualTo(9999);
        assertThat(loaded.inbox().channels()).containsExactlyInAnyOrder("qq", "test");
        assertThat(loaded.inbox().apiPort()).isEqualTo(19001);
        assertThat(loaded.inbox().mcpPath()).isEqualTo("/custom/mcp");
        assertThat(loaded.summary().debounceMillis()).isEqualTo(3000);
        assertThat(loaded.summary().maxMessagesPerDigest()).isEqualTo(12);
        assertThat(loaded.weighting().toSettings().signalWeights()
                .get(io.mosire.iibot.bot.weight.WeightSignal.MENTION)).isEqualTo(220);
        assertThat(loaded.weighting().toSettings().defaultKeywordWeights())
                .containsEntry("上线", 120);
    }
}
