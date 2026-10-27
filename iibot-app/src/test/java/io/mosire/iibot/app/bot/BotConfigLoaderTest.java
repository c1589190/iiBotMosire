package io.mosire.iibot.app.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsBotOnlyConfigAndResolvesRelativeInboxDir() throws Exception {
        Path config = tempDir.resolve("bot.json");
        Files.writeString(config, """
                {
                  "dataDir": "./data",
                  "accountId": "10001",
                  "napcat": {
                    "eventWebSocketUri": "ws://127.0.0.1:3001/",
                    "httpBaseUri": "http://127.0.0.1:3000/"
                  },
                  "control": {
                    "host": "127.0.0.1",
                    "port": 8765,
                    "path": "/mcp/bot",
                    "bearerTokenEnv": "IIBOT_CONTROL_TOKEN"
                  },
                  "inbox": {
                    "inboxId": "iibot-bot",
                    "bindingLabel": "dsh-bot",
                    "channels": ["qq"]
                  }
                }
                """);

        BotConfig loaded = BotConfigLoader.load(config);

        assertThat(loaded.dataDir()).isEqualTo(tempDir.resolve("data").toAbsolutePath());
        assertThat(loaded.inbox().dataDir())
                .isEqualTo(tempDir.resolve("data").resolve("inbox").toAbsolutePath());
        assertThat(loaded.inbox().channels()).isEqualTo(Set.of("qq"));
        assertThat(loaded.summary().debounceMillis()).isEqualTo(2_000L);
        assertThat(loaded.outbox().minSendIntervalMillis()).isEqualTo(150);
        assertThat(loaded.inbox().apiHost()).isEqualTo("127.0.0.1");
        assertThat(loaded.inbox().apiPort()).isZero();
        assertThat(loaded.inbox().mcpHost()).isEqualTo("127.0.0.1");
        assertThat(loaded.inbox().mcpPort()).isZero();
        assertThat(loaded.inbox().mcpPath()).isEqualTo("/mcp");
        assertThat(loaded.inbox().heartbeatMillis()).isEqualTo(15_000L);
        assertThat(loaded.inbox().claimSweepIntervalMillis()).isEqualTo(5_000L);
        assertThat(loaded.weighting().toSettings().defaultPrivateSourceWeight())
                .isEqualTo(300);
        assertThat(loaded.weighting().toSettings().defaultGroupSourceWeight())
                .isZero();
        assertThat(loaded.weighting().toSettings().maxMessagesPerDigest())
                .isEqualTo(20);
    }

    @Test
    void loadsInboxNetworkOverrides() throws Exception {
        Path config = tempDir.resolve("inbox-network.json");
        Files.writeString(config, """
                {
                  "dataDir": "./data", "accountId": "10001",
                  "napcat": {"eventWebSocketUri": "ws://127.0.0.1:3001/",
                             "httpBaseUri": "http://127.0.0.1:3000/"},
                  "control": {"bearerTokenEnv": "IIBOT_CONTROL_TOKEN"},
                  "inbox": {
                    "inboxId": "iibot-bot", "bindingLabel": "dsh-bot",
                    "apiHost": "localhost", "apiPort": 19001,
                    "mcpHost": "127.0.0.1", "mcpPort": 19002,
                    "mcpPath": "/custom/mcp",
                    "heartbeatMillis": 2500,
                    "claimSweepIntervalMillis": 300
                  }
                }
                """);

        BotConfig loaded = BotConfigLoader.load(config);

        assertThat(loaded.inbox().apiHost()).isEqualTo("localhost");
        assertThat(loaded.inbox().apiPort()).isEqualTo(19001);
        assertThat(loaded.inbox().mcpPort()).isEqualTo(19002);
        assertThat(loaded.inbox().mcpPath()).isEqualTo("/custom/mcp");
        assertThat(loaded.inbox().heartbeatMillis()).isEqualTo(2500L);
        assertThat(loaded.inbox().claimSweepIntervalMillis()).isEqualTo(300L);
    }

    @Test
    void loadsWeightingOverrides() throws Exception {
        Path config = tempDir.resolve("weighted.json");
        Files.writeString(config, """
                {
                  "dataDir": "./data", "accountId": "10001",
                  "napcat": {"eventWebSocketUri": "ws://127.0.0.1:3001/",
                             "httpBaseUri": "http://127.0.0.1:3000/"},
                  "control": {"bearerTokenEnv": "IIBOT_CONTROL_TOKEN"},
                  "inbox": {"inboxId": "iibot-bot", "bindingLabel": "dsh-bot"},
                  "weighting": {
                    "defaultPrivateSourceWeight": 400,
                    "defaultGroupSourceWeight": -20,
                    "defaultNotifyThreshold": 90,
                    "signalWeights": {"MENTION": 220},
                    "keywordWeights": {"上线": 120},
                    "aliases": ["洛洛"],
                    "commandPrefixes": ["/"],
                    "maxMessagesPerDigest": 12,
                    "quietMillis": 5000,
                    "cooldownMillis": 1000
                  }
                }
                """);

        BotConfig loaded = BotConfigLoader.load(config);

        assertThat(loaded.weighting().defaultPrivateSourceWeight()).isEqualTo(400);
        assertThat(loaded.weighting().defaultGroupSourceWeight()).isEqualTo(-20);
        assertThat(loaded.weighting().toSettings().signalWeights()
                .get(io.mosire.iibot.bot.weight.WeightSignal.MENTION)).isEqualTo(220);
        assertThat(loaded.weighting().toSettings().defaultKeywordWeights())
                .containsEntry("上线", 120);
        assertThat(loaded.weighting().toSettings().aliases()).containsExactly("洛洛");
        assertThat(loaded.weighting().maxMessagesPerDigest()).isEqualTo(12);
    }

    @Test
    void rejectsNonLoopbackInboxHost() throws Exception {
        Path config = tempDir.resolve("bad-inbox-host.json");
        Files.writeString(config, """
                {
                  "dataDir": "./data", "accountId": "1",
                  "napcat": {"eventWebSocketUri": "ws://127.0.0.1:1/",
                             "httpBaseUri": "http://127.0.0.1:1/"},
                  "control": {"bearerTokenEnv": "TOKEN"},
                  "inbox": {"inboxId": "iibot-bot", "bindingLabel": "dsh-bot",
                            "apiHost": "0.0.0.0"}
                }
                """);

        assertThatThrownBy(() -> BotConfigLoader.load(config))
                .isInstanceOf(io.mosire.iibot.app.config.ConfigException.class)
                .hasMessageContaining("回环");
    }

    @Test
    void rejectsMcpPathWithQuery() throws Exception {
        Path config = tempDir.resolve("bad-mcp-path.json");
        Files.writeString(config, """
                {
                  "dataDir": "./data", "accountId": "1",
                  "napcat": {"eventWebSocketUri": "ws://127.0.0.1:1/",
                             "httpBaseUri": "http://127.0.0.1:1/"},
                  "control": {"bearerTokenEnv": "TOKEN"},
                  "inbox": {"inboxId": "iibot-bot", "bindingLabel": "dsh-bot",
                            "mcpPath": "/mcp?x=1"}
                }
                """);

        assertThatThrownBy(() -> BotConfigLoader.load(config))
                .isInstanceOf(io.mosire.iibot.app.config.ConfigException.class)
                .hasMessageContaining("mcpPath");
    }

    @Test
    void rejectsGameFieldsAndUnknownFields() throws Exception {
        Path config = tempDir.resolve("bad.json");
        Files.writeString(config, """
                {
                  "dataDir": "./data", "accountId": "1",
                  "napcat": {"eventWebSocketUri": "ws://127.0.0.1:1/",
                             "httpBaseUri": "http://127.0.0.1:1/"},
                  "control": {"bearerTokenEnv": "TOKEN"},
                  "inbox": {},
                  "players": []
                }
                """);

        assertThatThrownBy(() -> BotConfigLoader.load(config))
                .hasMessageContaining("players");
    }
}
