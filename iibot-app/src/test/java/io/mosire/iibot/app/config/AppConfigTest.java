package io.mosire.iibot.app.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.iibot.app.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsCompleteConfigAndAbsolutizesPaths() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("facts.json"), "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("iibot.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));

        AppConfig config = TestSupport.loadConfig(configFile);

        assertThat(config.dataDir()).isAbsolute().isEqualTo(tempDir.resolve("data").toAbsolutePath());
        assertThat(config.publicGroupId()).isEqualTo("group-public");
        assertThat(config.maxTurnMinutes()).isEqualTo(240);
        assertThat(config.players()).extracting(AppConfig.Account::playerId)
                .containsExactly("player-1", "player-2");
        assertThat(config.directors()).extracting(AppConfig.Account::playerId)
                .containsExactly("director-1");
        assertThat(config.world().factsFile()).isAbsolute().isEqualTo(facts.toAbsolutePath());
        assertThat(config.llm()).isPresent();
        assertThat(config.llm().orElseThrow().model()).isEqualTo("fake-model");
        assertThat(config.dispatcher().maxConcurrentSessions()).isEqualTo(64);
        assertThat(config.outbox().sendTimeoutSeconds()).isEqualTo(15);
    }

    @Test
    void rejectsMissingRequiredField() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("missing.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        ObjectNode root = readNode(configFile);
        root.remove("publicGroupId");
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("publicGroupId");
    }

    @Test
    void rejectsUnknownFieldExplicitly() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("unknown.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        ObjectNode root = readNode(configFile);
        root.put("surprise", true);
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("未知字段")
                .hasMessageContaining("surprise");
    }

    @Test
    void rejectsDuplicateQqAcrossPlayersAndDirectors() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("dup-qq.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        ObjectNode root = readNode(configFile);
        ((ObjectNode) root.get("directors").get(0)).put("qqId", "qq-p1");
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("重复 QQ");
    }

    @Test
    void rejectsDuplicateActorAndPlayerId() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("dup-actor.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        ObjectNode root = readNode(configFile);
        ArrayNode players = (ArrayNode) root.get("players");
        ((ObjectNode) players.get(1)).put("playerId", "player-1");
        ((ObjectNode) players.get(1)).put("actorId", "player-1");
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("重复 actor/playerId");
    }

    @Test
    void rejectsInvalidPath() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("bad-path.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        ObjectNode root = readNode(configFile);
        root.put("dataDir", "bad\u0000path");
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("dataDir");
    }

    @Test
    void toStringNeverContainsSecretValueAndFactoryDoesNotSendNetwork() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("secret.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        AppConfig config = TestSupport.loadConfig(configFile);
        String secret = "super-secret-value-should-never-appear";
        SecretProvider secrets = SecretProvider.of(Map.of("IIBOT_LLM_KEY", secret,
                "IIBOT_NAPCAT_TOKEN", "napcat-secret-token"));

        LlmClient client = LlmClientFactory.create(config.llm().orElseThrow(), secrets);

        assertThat(client).isNotNull();
        assertThat(config.toString()).doesNotContain(secret).doesNotContain("napcat-secret-token");
        assertThat(config.llm().orElseThrow().toString()).doesNotContain(secret);
        assertThat(config.napcat().toString()).doesNotContain("secret-value");
    }

    @Test
    void controlConfigDefaultsDisabledAndParsesLoopbackBearerConfig() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("control-off.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        AppConfig disabled = TestSupport.loadConfig(configFile);
        assertThat(disabled.control().enabled()).isFalse();

        ObjectNode root = readNode(configFile);
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 8765);
        control.put("path", "/mcp/control");
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        control.put("maxRequestBytes", 1024);
        TestSupport.writeJson(configFile, root);

        AppConfig enabled = TestSupport.loadConfig(configFile);
        assertThat(enabled.control().enabled()).isTrue();
        assertThat(enabled.control().host()).isEqualTo("127.0.0.1");
        assertThat(enabled.control().path()).isEqualTo("/mcp/control");
        assertThat(enabled.control().bearerTokenEnv()).isEqualTo("IIBOT_CONTROL_TOKEN");
        assertThat(enabled.toString()).doesNotContain("Bearer ");
    }

    @Test
    void controlEnabledWithoutBearerTokenEnvFailsClosed() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("control-no-token.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        ObjectNode root = readNode(configFile);
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("bearerTokenEnv");
    }

    @Test
    void llmFactoryFailsClosedWhenKeyMissing() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("no-key.json"),
                TestSupport.validConfigNode(tempDir.resolve("data"), tempDir.resolve("facts.json")));
        AppConfig config = TestSupport.loadConfig(configFile);

        assertThatThrownBy(() -> LlmClientFactory.create(config.llm().orElseThrow(),
                SecretProvider.of(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IIBOT_LLM_KEY")
                .hasMessageNotContaining("super-secret");
    }

    private static ObjectNode readNode(Path file) {
        try {
            return (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    java.nio.file.Files.readString(file));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
