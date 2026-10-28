package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.ConfigException;
import io.mosire.iibot.app.config.SecretProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigLoaderTest {

    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_CONTROL_TOKEN", "control-secret-value",
            "IIBOT_NAPCAT_ACCESS_TOKEN", "napcat-secret-value"));

    @TempDir
    Path tempDir;

    @Test
    void loadsSingleAccountSchemaAndResolvesRelativePaths() throws Exception {
        Path config = write("bot.json", validConfig("main", "10001", true));

        BotConfig loaded = BotConfigLoader.load(config, SECRETS);

        assertThat(loaded.dataDir()).isEqualTo(tempDir.resolve("data").toAbsolutePath());
        assertThat(loaded.accounts()).containsOnlyKeys("main");
        assertThat(loaded.firstEnabledAlias()).isEqualTo("main");
        assertThat(loaded.activeAccount().accountId()).isEqualTo("10001");
        assertThat(loaded.activeAccount().adapter()).isEqualTo("onebot11");
        assertThat(loaded.activeAccount().oneBot().accessTokenEnv())
                .contains("IIBOT_NAPCAT_ACCESS_TOKEN");
        assertThat(loaded.inbox().dataDir())
                .isEqualTo(tempDir.resolve("data").resolve("inbox").toAbsolutePath());
        assertThat(loaded.inbox().channels()).isEqualTo(Set.of("qq"));
        assertThat(loaded.messageBoard().summaryDebounceMillis()).isEqualTo(2_000L);
        assertThat(loaded.messageBoard().maxPreviewMessages()).isEqualTo(20);
        assertThat(loaded.messageBoard().outbox().minSendIntervalMillis()).isEqualTo(150);
        assertThat(loaded.messageBoard().weighting().toSettings(20)
                .defaultPrivateSourceWeight()).isEqualTo(300);
        assertThat(loaded.logging().directory())
                .isEqualTo(tempDir.resolve("logs").toAbsolutePath());
        assertThat(loaded.logging().levels())
                .containsEntry("io.mosire.iibot.connect", "DEBUG");
    }

    @Test
    void loadsMultiAccountAndSelectsFirstEnabledForP3Runtime() throws Exception {
        Path config = write("multi.json", """
                {
                  "dataDir": "./data",
                  "accounts": {
                    "disabled": {
                      "enabled": false,
                      "adapter": "onebot11",
                      "accountId": "20001",
                      "oneBot": {
                        "eventWebSocketUri": "ws://127.0.0.1:3001/",
                        "httpBaseUri": "http://127.0.0.1:3000/",
                        "accessTokenEnv": "IIBOT_NAPCAT_ACCESS_TOKEN"
                      }
                    },
                    "secondary": {
                      "enabled": true,
                      "adapter": "onebot11",
                      "accountId": "20002",
                      "oneBot": {
                        "eventWebSocketUri": "ws://127.0.0.1:3011/",
                        "httpBaseUri": "http://127.0.0.1:3010/",
                        "accessTokenEnv": "IIBOT_NAPCAT_ACCESS_TOKEN"
                      }
                    }
                  },
                  "control": {"bearerTokenEnv": "IIBOT_CONTROL_TOKEN"},
                  "inbox": {}
                }
                """);

        BotConfig loaded = BotConfigLoader.load(config, SECRETS);

        assertThat(loaded.accounts()).containsOnlyKeys("disabled", "secondary");
        assertThat(loaded.firstEnabledAlias()).isEqualTo("secondary");
        assertThat(loaded.activeAccount().accountId()).isEqualTo("20002");
    }

    @Test
    void disabledAccountsDoNotRequireTheirOptionalTokenEnv() throws Exception {
        String configText = validConfig("main", "10001", true)
                .replace("\"accounts\": {", """
                        "accounts": {
                          "disabled": {
                            "enabled": false,
                            "adapter": "onebot11",
                            "accountId": "20001",
                            "oneBot": {
                              "eventWebSocketUri": "ws://127.0.0.1:3011/",
                              "httpBaseUri": "http://127.0.0.1:3010/",
                              "accessTokenEnv": "IIBOT_DISABLED_ACCOUNT_TOKEN"
                            }
                          },
                        """);
        Path config = write("disabled-secret.json", configText);

        BotConfig loaded = BotConfigLoader.load(config, SECRETS);

        assertThat(loaded.firstEnabledAlias()).isEqualTo("main");
        assertThat(loaded.accounts()).containsKey("disabled");
    }

    @Test
    void rejectsUnknownFieldWithFilePointerAndLegacyHint() throws Exception {
        Path config = write("legacy.json", validConfig("main", "10001", true)
                .replaceFirst("\\{", "{\n  \"napcat\": {},"));

        assertThatThrownBy(() -> BotConfigLoader.load(config, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("CONFIG_INVALID")
                .hasMessageContaining(fileLine(config))
                .hasMessageContaining("jsonPointer: /napcat")
                .hasMessageContaining("expected: known field")
                .hasMessageContaining("actual: unknown field")
                .hasMessageContaining("accounts.<alias>.oneBot");
    }

    @Test
    void rejectsWrongTypeAndMissingFieldWithJsonPointer() throws Exception {
        Path wrongType = write("wrong-type.json", validConfig("main", "10001", true)
                .replace("\"maxTextLength\": 2000", "\"maxTextLength\": \"2000\""));
        assertThatThrownBy(() -> BotConfigLoader.load(wrongType, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(fileLine(wrongType))
                .hasMessageContaining(
                        "jsonPointer: /accounts/main/oneBot/maxTextLength")
                .hasMessageContaining("expected: integer")
                .hasMessageContaining("actual: string");

        Path missing = write("missing.json", validConfig("main", "10001", true)
                .replace("        \"httpBaseUri\": \"http://127.0.0.1:3000/\",\n", ""));
        assertThatThrownBy(() -> BotConfigLoader.load(missing, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(fileLine(missing))
                .hasMessageContaining(
                        "jsonPointer: /accounts/main/oneBot/httpBaseUri")
                .hasMessageContaining("actual: missing");
    }

    @Test
    void rejectsInvalidUriNonLoopbackAndDuplicateAccountId() throws Exception {
        Path badUri = write("bad-uri.json", validConfig("main", "10001", true)
                .replace("http://127.0.0.1:3000/", "not-a-uri"));
        assertThatThrownBy(() -> BotConfigLoader.load(badUri, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(
                        "jsonPointer: /accounts/main/oneBot/httpBaseUri")
                .hasMessageContaining("expected: http|https URI")
                .hasMessageContaining(fileLine(badUri));

        Path badHost = write("bad-host.json", validConfig("main", "10001", true)
                .replace("\"host\": \"127.0.0.1\"", "\"host\": \"0.0.0.0\""));
        assertThatThrownBy(() -> BotConfigLoader.load(badHost, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("jsonPointer: /control/host")
                .hasMessageContaining("expected: loopback host")
                .hasMessageContaining("actual: string");

        String duplicateId = validConfig("main", "10001", true)
                .replace("\"accounts\": {", """
                        "accounts": {
                          "second": {
                            "enabled": true,
                            "adapter": "onebot11",
                            "accountId": "10001",
                            "oneBot": {
                              "eventWebSocketUri": "ws://127.0.0.1:3001/",
                              "httpBaseUri": "http://127.0.0.1:3000/"
                            }
                          },
                        """);
        Path duplicate = write("duplicate-id.json", duplicateId);
        assertThatThrownBy(() -> BotConfigLoader.load(duplicate, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("jsonPointer: /accounts/main/accountId")
                .hasMessageContaining("globally unique accountId")
                .hasMessageContaining(fileLine(duplicate));
    }

    @Test
    void rejectsDuplicateAliasAtJsonReadTime() throws Exception {
        String duplicateAlias = """
                {
                  "dataDir": "./data",
                  "accounts": {
                    "same": {
                      "enabled": true,
                      "adapter": "onebot11",
                      "accountId": "10001",
                      "oneBot": {
                        "eventWebSocketUri": "ws://127.0.0.1:3001/",
                        "httpBaseUri": "http://127.0.0.1:3000/"
                      }
                    },
                    "same": {
                      "enabled": true,
                      "adapter": "onebot11",
                      "accountId": "10002",
                      "oneBot": {
                        "eventWebSocketUri": "ws://127.0.0.1:3001/",
                        "httpBaseUri": "http://127.0.0.1:3000/"
                      }
                    }
                  },
                  "control": {"bearerTokenEnv": "IIBOT_CONTROL_TOKEN"},
                  "inbox": {}
                }
                """;
        Path config = write("duplicate-alias.json", duplicateAlias);

        assertThatThrownBy(() -> BotConfigLoader.load(config, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(fileLine(config))
                .hasMessageContaining("jsonPointer:")
                .hasMessageContaining("重复字段")
                .hasMessageContaining("duplicate key");
    }

    @Test
    void reportsMissingSecretEnvAtPointerWithoutLeakingValues() throws Exception {
        Path config = write("bot.json", validConfig("main", "10001", true));

        assertThatThrownBy(() -> BotConfigLoader.load(config, SecretProvider.of(Map.of())))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(fileLine(config))
                .hasMessageContaining("jsonPointer: /control/bearerTokenEnv")
                .hasMessageContaining("expected: non-empty environment variable "
                        + "IIBOT_CONTROL_TOKEN")
                .hasMessageContaining("actual: MISSING")
                .hasMessageNotContaining("control-secret-value");
    }

    @Test
    void detailedLoadCollectsMissingSecretsForDiagnostics() throws Exception {
        Path config = write("bot.json", validConfig("main", "10001", true));

        BotConfigLoader.LoadResult result = BotConfigLoader.loadDetailed(
                java.util.List.of(config), SecretProvider.of(Map.of()));

        assertThat(result.config().activeAccount().accountId()).isEqualTo("10001");
        assertThat(result.secretReady()).isFalse();
        assertThat(result.secretErrors()).hasSize(2);
        assertThat(result.secretErrors()).extracting(ConfigException::jsonPointer)
                .containsExactlyInAnyOrder("/control/bearerTokenEnv",
                        "/accounts/main/oneBot/accessTokenEnv");
    }

    @Test
    void rejectsUnknownLoggingPackageAndInvalidLevel() throws Exception {
        Path unknownPackage = write("unknown-logger.json",
                validConfig("main", "10001", true)
                        .replace("\"io.mosire.iibot.bot\": \"DEBUG\"",
                                "\"com.example.unknown\": \"DEBUG\""));
        assertThatThrownBy(() -> BotConfigLoader.load(unknownPackage, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("jsonPointer: /logging/levels/com.example.unknown")
                .hasMessageContaining("known logger package");

        Path badLevel = write("bad-level.json", validConfig("main", "10001", true)
                .replace("\"rootLevel\": \"INFO\"", "\"rootLevel\": \"LOUD\""));
        assertThatThrownBy(() -> BotConfigLoader.load(badLevel, SECRETS))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("jsonPointer: /logging")
                .hasMessageContaining("TRACE/DEBUG/INFO/WARN/ERROR/OFF");
    }

    private Path write(String name, String json) throws Exception {
        return Files.writeString(tempDir.resolve(name), json);
    }

    private String fileLine(Path file) {
        return "file: " + file.toAbsolutePath().normalize();
    }

    private static String validConfig(String alias, String accountId, boolean enabled) {
        return """
                {
                  "$schema": "../schema/bot-config.schema.json",
                  "dataDir": "./data",
                  "accounts": {
                    "%s": {
                      "enabled": %s,
                      "adapter": "onebot11",
                      "accountId": "%s",
                      "oneBot": {
                        "eventWebSocketUri": "ws://127.0.0.1:3001/",
                        "httpBaseUri": "http://127.0.0.1:3000/",
                        "accessTokenEnv": "IIBOT_NAPCAT_ACCESS_TOKEN",
                        "privateTempGroupId": "",
                        "suppressSelfMessages": true,
                        "requestTimeoutSeconds": 10,
                        "maxTextLength": 2000
                      }
                    }
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
                  },
                  "messageBoard": {
                    "summaryDebounceMillis": 2000,
                    "maxPreviewMessages": 20,
                    "outbox": {
                      "sendTimeoutSeconds": 15,
                      "minSendIntervalMillis": 150
                    },
                    "weighting": {
                      "defaultPrivateSourceWeight": 300,
                      "defaultGroupSourceWeight": 0,
                      "defaultNotifyThreshold": 100,
                      "signalWeights": {"MENTION": 200},
                      "keywordWeights": {},
                      "aliases": [],
                      "commandPrefixes": ["/", "!"],
                      "quietMillis": 180000,
                      "cooldownMillis": 60000
                    }
                  },
                  "logging": {
                    "directory": "./logs",
                    "console": true,
                    "file": true,
                    "rootLevel": "INFO",
                    "opsLevel": "INFO",
                    "levels": {
                      "io.mosire.iibot.connect": "DEBUG",
                      "io.mosire.iibot.bot": "DEBUG",
                      "io.mosire.iibot.app": "INFO"
                    },
                    "maxFileSize": "20MB",
                    "maxHistory": 30
                  }
                }
                """.formatted(alias, enabled, accountId);
    }
}
