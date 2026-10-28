package io.mosire.iibot.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.app.config.SecretProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MainConfigCliTest {

    private static final String CONTROL_SECRET = "cli-control-secret-9f7a";
    private static final String NAPCAT_SECRET = "cli-napcat-secret-3b2c";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void noConfigBootstrapsSingleFileWithRc2WithoutStartingRuntime() throws Exception {
        Path configDir = tempDir.resolve("config/bot");
        Captured captured = execute(new String[]{}, configDir, SecretProvider.of(Map.of()));

        assertThat(captured.rc()).isEqualTo(2);
        assertThat(configDir.resolve("bot.json")).isRegularFile();
        assertThat(configDir.resolve("00-base.json")).doesNotExist();
        assertThat(captured.err()).contains("已生成完整模板")
                .contains("IIBOT_CONTROL_TOKEN: MISSING")
                .contains("未启动 QQ/Inbox/MCP");
    }

    @Test
    void checkConfigValidatesWithoutRuntimeAndPrintsSetStatusAndSanitizedJson()
            throws Exception {
        Path config = writeValidConfig("bot.json");
        Captured captured = execute(new String[]{"--check-config", "--config",
                config.toString()}, tempDir.resolve("default"), secrets());

        assertThat(captured.rc()).isZero();
        assertThat(captured.out()).contains("CONFIG_VALID")
                .contains("sources:")
                .contains(config.toAbsolutePath().normalize().toString())
                .contains("IIBOT_CONTROL_TOKEN: SET")
                .contains("IIBOT_NAPCAT_ACCESS_TOKEN: SET")
                .contains("effectiveConfig:")
                .doesNotContain(CONTROL_SECRET)
                .doesNotContain(NAPCAT_SECRET);
        assertThat(captured.err()).isEmpty();
    }

    @Test
    void checkConfigReportsMissingSecretWithFileAndPointerAndRc1() throws Exception {
        Path config = writeValidConfig("missing-secret.json");
        Captured captured = execute(new String[]{"--check-config", "--config",
                config.toString()}, tempDir.resolve("default"), SecretProvider.of(Map.of()));

        assertThat(captured.rc()).isEqualTo(1);
        assertThat(captured.out()).contains("CONFIG_INVALID")
                .contains("<env:IIBOT_CONTROL_TOKEN:MISSING>")
                .contains("<env:IIBOT_NAPCAT_ACCESS_TOKEN:MISSING>")
                .doesNotContain(CONTROL_SECRET)
                .doesNotContain(NAPCAT_SECRET);
        assertThat(captured.err())
                .contains("file: " + config.toAbsolutePath().normalize())
                .contains("jsonPointer: /control/bearerTokenEnv")
                .contains("actual: MISSING")
                .contains("IIBOT_CONTROL_TOKEN");
    }

    @Test
    void printEffectiveConfigOutputsParseableJsonWithZeroSecretValues() throws Exception {
        Path config = writeValidConfig("effective.json");
        Captured captured = execute(new String[]{"--print-effective-config",
                "--log-level", "DEBUG", "--config", config.toString()},
                tempDir.resolve("default"), secrets());

        assertThat(captured.rc()).isZero();
        assertThat(captured.out()).doesNotContain(CONTROL_SECRET)
                .doesNotContain(NAPCAT_SECRET)
                .contains("<env:IIBOT_CONTROL_TOKEN:SET>")
                .contains("<env:IIBOT_NAPCAT_ACCESS_TOKEN:SET>");
        JsonNode tree = JSON.readTree(captured.out());
        assertThat(tree.path("accounts").path("main").path("accountId").asText())
                .isEqualTo("10001");
        assertThat(tree.path("logging").path("rootLevel").asText()).isEqualTo("DEBUG");
        assertThat(tree.path("logging").path("opsLevel").asText()).isEqualTo("DEBUG");
        assertThat(captured.err()).isEmpty();
    }

    @Test
    void invalidFieldWritesStructuredErrorToStderrOnly() throws Exception {
        Path config = Files.writeString(tempDir.resolve("invalid.json"),
                writeValidConfigText().replace("  \"control\": {",
                        "  \"unknownTop\": true,\n  \"control\": {"));
        Captured captured = execute(new String[]{"--check-config", "--config",
                config.toString()}, tempDir.resolve("default"), secrets());

        assertThat(captured.rc()).isEqualTo(1);
        assertThat(captured.out()).isEmpty();
        assertThat(captured.err())
                .contains("CONFIG_INVALID")
                .contains("file: " + config.toAbsolutePath().normalize())
                .contains("jsonPointer: /unknownTop")
                .contains("expected: known field")
                .contains("actual: unknown field")
                .contains("hint:");
    }

    private Captured execute(String[] args, Path defaultSource, SecretProvider secrets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = Main.execute(args, defaultSource, secrets,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Captured(rc, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private SecretProvider secrets() {
        return SecretProvider.of(Map.of(
                "IIBOT_CONTROL_TOKEN", CONTROL_SECRET,
                "IIBOT_NAPCAT_ACCESS_TOKEN", NAPCAT_SECRET));
    }

    private Path writeValidConfig(String name) throws Exception {
        return Files.writeString(tempDir.resolve(name), writeValidConfigText());
    }

    private String writeValidConfigText() {
        return """
                {
                  "dataDir": "./data",
                  "accounts": {
                    "main": {
                      "enabled": true,
                      "adapter": "onebot11",
                      "accountId": "10001",
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
                  "inbox": {"inboxId": "iibot-bot", "channels": ["qq"]},
                  "messageBoard": {
                    "summaryDebounceMillis": 2000,
                    "maxPreviewMessages": 20
                  },
                  "logging": {
                    "directory": "./logs",
                    "console": false,
                    "file": true,
                    "rootLevel": "INFO",
                    "opsLevel": "INFO",
                    "levels": {"io.mosire.iibot.app": "DEBUG"},
                    "maxFileSize": "20MB",
                    "maxHistory": 30
                  }
                }
                """;
    }

    private record Captured(int rc, String out, String err) {
    }
}
