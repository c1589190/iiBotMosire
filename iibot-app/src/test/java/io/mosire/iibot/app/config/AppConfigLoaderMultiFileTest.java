package io.mosire.iibot.app.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigLoaderMultiFileTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesLegacyConfigFragmentsInOrder() throws Exception {
        Path facts = TestSupport.writeFacts(tempDir.resolve("world.json"), """
                {"facts":[{"factId":"f1","content":"public","visibility":"PUBLIC"}]}
                """);
        ObjectNode baseNode = TestSupport.validConfigNode(tempDir.resolve("data"), facts);
        Path base = TestSupport.writeConfig(tempDir.resolve("00-base.json"), baseNode);
        Path override = Files.writeString(tempDir.resolve("10-local.json"), """
                {
                  "outbox": {"sendTimeoutSeconds": 20, "minSendIntervalMillis": 150},
                  "inbox": {"enabled": false, "inboxId": "iibot-app-test"}
                }
                """);

        AppConfig config = AppConfigLoader.load(List.of(base, override));

        assertThat(config.outbox().sendTimeoutSeconds()).isEqualTo(20);
        assertThat(config.inbox().inboxId()).isEqualTo("iibot-app-test");
        assertThat(config.players()).hasSize(2);
    }
}
