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
    void loadsEmptyPlayersWithDirectorsAndControlEnabled() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("no-players-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("no-players.json"),
                TestSupport.validConfigNode(tempDir.resolve("no-players-data"), facts));
        ObjectNode root = readNode(configFile);
        ((ArrayNode) root.get("players")).removeAll();
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 8765);
        control.put("path", "/mcp/control");
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        TestSupport.writeJson(configFile, root);

        AppConfig config = TestSupport.loadConfig(configFile);

        assertThat(config.players()).isEmpty();
        assertThat(config.directors()).extracting(AppConfig.Account::playerId)
                .containsExactly("director-1");
        assertThat(config.control().enabled()).isTrue();
    }

    @Test
    void loadsEmptyPlayersWithControlDisabledBecauseProductionOnlyWarns() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("no-players-control-off-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(
                tempDir.resolve("no-players-control-off.json"),
                TestSupport.validConfigNode(
                        tempDir.resolve("no-players-control-off-data"), facts));
        ObjectNode root = readNode(configFile);
        ((ArrayNode) root.get("players")).removeAll();
        root.remove("control");
        TestSupport.writeJson(configFile, root);

        AppConfig config = TestSupport.loadConfig(configFile);

        assertThat(config.players()).isEmpty();
        assertThat(config.directors()).isNotEmpty();
        assertThat(config.control().enabled()).isFalse();
    }

    @Test
    void rejectsEmptyDirectorsEvenWhenPlayersAreEmpty() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("no-directors-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("no-directors.json"),
                TestSupport.validConfigNode(tempDir.resolve("no-directors-data"), facts));
        ObjectNode root = readNode(configFile);
        ((ArrayNode) root.get("players")).removeAll();
        ((ArrayNode) root.get("directors")).removeAll();
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("directors")
                .hasMessageContaining("不能为空数组");
    }

    @Test
    void rejectsMissingPlayersFieldEvenWhenPlayersMayBeEmpty() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("missing-players-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("missing-players.json"),
                TestSupport.validConfigNode(tempDir.resolve("missing-players-data"), facts));
        ObjectNode root = readNode(configFile);
        root.remove("players");
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("players");
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
    void loadsNamedLlmRoutesAndAgentRouteFallbacks() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("routes-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("routes.json"),
                TestSupport.validConfigNode(tempDir.resolve("data-routes"), facts));
        ObjectNode root = readNode(configFile);
        ObjectNode routes = root.putObject("llmRoutes");
        routes.set("strong", llmNode("http://127.0.0.1:2/v1", "strong-model",
                "IIBOT_STRONG_KEY"));
        routes.set("aide.model-1", llmNode("http://127.0.0.1:3/v1", "aide-model",
                "IIBOT_AIDE_KEY"));
        ObjectNode agents = root.putObject("agentRoutes");
        agents.put("secretary", "strong");
        agents.put("gmAide", "aide.model-1");
        TestSupport.writeJson(configFile, root);

        AppConfig config = TestSupport.loadConfig(configFile);

        assertThat(config.llmRoutes()).isPresent();
        assertThat(config.llmRoutes().orElseThrow()).containsOnlyKeys("strong", "aide.model-1");
        assertThat(config.agentRoute(AppConfig.AGENT_SECRETARY)).isEqualTo("strong");
        assertThat(config.agentRoute(AppConfig.AGENT_ADJUDICATOR))
                .isEqualTo(AppConfig.DEFAULT_LLM_ROUTE);
        assertThat(config.agentRoute(AppConfig.AGENT_GM_AIDE)).isEqualTo("aide.model-1");
        assertThat(config.llmForRoute("strong").model()).isEqualTo("strong-model");
        assertThat(config.effectiveLlmRoutes())
                .containsKeys(AppConfig.DEFAULT_LLM_ROUTE, "strong", "aide.model-1");
        assertThat(config.toString())
                .contains("strong", "aide.model-1")
                .doesNotContain("IIBOT_STRONG_KEY_VALUE");
    }

    @Test
    void absentLlmRoutesAndAgentRoutesFallBackCompletelyToDefault() {
        Path configFile = TestSupport.writeConfig(tempDir.resolve("legacy.json"),
                TestSupport.validConfigNode(tempDir.resolve("legacy-data"),
                        tempDir.resolve("legacy-facts.json")));

        AppConfig config = TestSupport.loadConfig(configFile);

        assertThat(config.llmRoutes()).isEmpty();
        assertThat(config.agentRoutes()).isEmpty();
        assertThat(config.agentRoute(AppConfig.AGENT_SECRETARY))
                .isEqualTo(AppConfig.DEFAULT_LLM_ROUTE);
        assertThat(config.agentRoute(AppConfig.AGENT_ADJUDICATOR))
                .isEqualTo(AppConfig.DEFAULT_LLM_ROUTE);
        assertThat(config.agentRoute(AppConfig.AGENT_GM_AIDE))
                .isEqualTo(AppConfig.DEFAULT_LLM_ROUTE);
        assertThat(config.effectiveLlmRoutes()).containsOnlyKeys(
                AppConfig.DEFAULT_LLM_ROUTE);
    }

    @Test
    void rejectsUnknownFieldInsideNamedRouteAndAgentRoutes() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("bad-route-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("bad-route.json"),
                TestSupport.validConfigNode(tempDir.resolve("bad-route-data"), facts));
        ObjectNode root = readNode(configFile);
        ObjectNode route = llmNode("http://127.0.0.1:2/v1", "strong", "KEY");
        route.put("surprise", true);
        root.putObject("llmRoutes").set("strong", route);
        TestSupport.writeJson(configFile, root);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("llmRoutes.strong")
                .hasMessageContaining("未知字段")
                .hasMessageContaining("surprise");

        ObjectNode root2 = readNode(configFile);
        root2.remove("llmRoutes");
        ObjectNode agents = root2.putObject("agentRoutes");
        agents.put("unexpected", "strong");
        TestSupport.writeJson(configFile, root2);
        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("agentRoutes")
                .hasMessageContaining("未知字段");
    }

    @Test
    void rejectsUnsafeAndUndefinedAgentRouteNames() {
        Path facts = TestSupport.writeFacts(tempDir.resolve("route-name-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("unsafe-route.json"),
                TestSupport.validConfigNode(tempDir.resolve("unsafe-data"), facts));
        ObjectNode root = readNode(configFile);
        root.putObject("llmRoutes").set("bad route", llmNode(
                "http://127.0.0.1:2/v1", "strong", "KEY"));
        TestSupport.writeJson(configFile, root);
        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("安全字符集");

        ObjectNode root2 = readNode(TestSupport.writeConfig(
                tempDir.resolve("undefined-route.json"),
                TestSupport.validConfigNode(tempDir.resolve("undefined-data"), facts)));
        ObjectNode agents = root2.putObject("agentRoutes");
        agents.put("adjudicator", "missing");
        TestSupport.writeJson(tempDir.resolve("undefined-route.json"), root2);
        assertThatThrownBy(() -> TestSupport.loadConfig(tempDir.resolve("undefined-route.json")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("未定义的 LLM route");
    }

    @Test
    void rejectsDuplicateRouteNameAtJsonParseTime() throws Exception {
        Path facts = TestSupport.writeFacts(tempDir.resolve("dup-route-facts.json"),
                "{\"facts\":[]}");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("dup-route.json"),
                TestSupport.validConfigNode(tempDir.resolve("dup-route-data"), facts));
        ObjectNode root = readNode(configFile);
        ObjectNode route = llmNode("http://127.0.0.1:2/v1", "strong", "KEY");
        root.putObject("llmRoutes").set("dup", route);
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(root);
        String routeJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(route);
        int objectStart = json.indexOf('{', json.indexOf("\"llmRoutes\""));
        String duplicate = json.substring(0, objectStart + 1)
                + "\n\"dup\": " + routeJson + ",\n\"dup\": " + routeJson + ","
                + json.substring(objectStart + 1);
        java.nio.file.Files.writeString(configFile, duplicate);

        assertThatThrownBy(() -> TestSupport.loadConfig(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("重复字段");
    }

    private static ObjectNode llmNode(String baseUrl, String model, String apiKeyEnv) {
        ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("baseUrl", baseUrl);
        node.put("model", model);
        node.put("apiKeyEnv", apiKeyEnv);
        node.put("connectTimeoutSeconds", 3);
        node.put("readTimeoutSeconds", 7);
        node.put("echoReasoningContent", false);
        return node;
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
