package io.mosire.iibot.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.app.bot.BotConfig;
import io.mosire.iibot.app.bot.BotConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigAssetsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_CONTROL_TOKEN", "test-control-secret",
            "IIBOT_NAPCAT_ACCESS_TOKEN", "test-onebot-secret",
            "IIBOT_NAPCAT_MAIN_TOKEN", "test-main-secret",
            "IIBOT_NAPCAT_SECONDARY_TOKEN", "test-secondary-secret",
            "IIBOT_BILI_CREDENTIAL_FILE", "/tmp/bili-cookie.json"));

    @Test
    void examplesLoadAgainstCurrentLoaderAndSchemaShape() throws Exception {
        Path configDir = configDirectory();
        BotConfig single = BotConfigLoader.load(
                configDir.resolve("examples/bot.full.json"), SECRETS);
        assertThat(single.accounts()).containsOnlyKeys("main");
        assertThat(single.firstEnabledAlias()).isEqualTo("main");

        BotConfig multi = BotConfigLoader.load(
                configDir.resolve("examples/bot.multi-account.json"), SECRETS);
        assertThat(multi.accounts()).containsOnlyKeys("main", "secondary");
        assertThat(multi.accounts().values()).extracting(BotConfig.Account::accountId)
                .containsExactlyInAnyOrder("100000001", "100000002");

        BotConfig bili = BotConfigLoader.load(
                configDir.resolve("examples/bot.bilibili.json"), SECRETS);
        assertThat(bili.accounts()).containsOnlyKeys("bili-main");
        assertThat(bili.activeAccount().adapter()).isEqualTo("bilibili");
        assertThat(bili.activeAccount().bilibili().credentialFileEnv())
                .isEqualTo("IIBOT_BILI_CREDENTIAL_FILE");

        JsonNode schema = JSON.readTree(
                configDir.resolve("schema/bot-config.schema.json").toFile());
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("dataDir", "accounts", "control", "inbox");
        JsonNode account = schema.path("$defs").path("account");
        assertThat(account.path("additionalProperties").asBoolean()).isFalse();
        assertThat(account.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("enabled", "adapter", "accountId");
        assertThat(account.path("properties").path("adapter").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("onebot11", "bilibili");
        JsonNode bilibili = schema.path("$defs").path("bilibili");
        assertThat(bilibili.path("required")).extracting(JsonNode::asText)
                .containsExactly("credentialFileEnv");
        JsonNode oneBot = schema.path("$defs").path("oneBot");
        assertThat(oneBot.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("eventWebSocketUri", "httpBaseUri");
        assertThat(schema.path("$defs").path("logging").path("properties").path("levels")
                .path("propertyNames").path("pattern").asText())
                .contains("io\\.mosire\\.iibot\\.(connect|bot|app|inbox)");
    }

    private static Path configDirectory() {
        Path fromModule = Path.of("..", "config").toAbsolutePath().normalize();
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        return Path.of("config").toAbsolutePath().normalize();
    }
}
