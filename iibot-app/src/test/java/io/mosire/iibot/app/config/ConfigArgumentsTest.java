package io.mosire.iibot.app.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigArgumentsTest {

    private static final Path DEFAULT = Path.of("config", "bot");

    @Test
    void noConfigUsesDefaultDirectory() {
        ConfigArguments.ParseResult parsed = ConfigArguments.parse(new String[0], DEFAULT);

        assertThat(parsed.sources()).containsExactly(DEFAULT);
        assertThat(parsed.helpRequested()).isFalse();
        assertThat(parsed.explicitConfig()).isFalse();
        assertThat(parsed.diagnosticRequested()).isFalse();
        assertThat(parsed.logLevel()).isEmpty();
    }

    @Test
    void supportsRepeatedAndEqualsFormConfigSources() {
        ConfigArguments.ParseResult parsed = ConfigArguments.parse(
                new String[]{"--config", "a.json", "--config=b.json"}, DEFAULT);

        assertThat(parsed.sources()).containsExactly(Path.of("a.json"), Path.of("b.json"));
        assertThat(parsed.explicitConfig()).isTrue();
    }

    @Test
    void parsesDiagnosticAndLogLevelOptions() {
        ConfigArguments.ParseResult parsed = ConfigArguments.parse(new String[]{
                "--check-config", "--log-level", "debug", "--config", "bot.json"}, DEFAULT);

        assertThat(parsed.checkConfig()).isTrue();
        assertThat(parsed.printEffectiveConfig()).isFalse();
        assertThat(parsed.diagnosticRequested()).isTrue();
        assertThat(parsed.logLevel()).contains("DEBUG");

        ConfigArguments.ParseResult print = ConfigArguments.parse(new String[]{
                "--print-effective-config", "--log-level=WARN"}, DEFAULT);
        assertThat(print.printEffectiveConfig()).isTrue();
        assertThat(print.logLevel()).contains("WARN");
    }

    @Test
    void helpDoesNotRequireConfig() {
        ConfigArguments.ParseResult parsed = ConfigArguments.parse(
                new String[]{"--help"}, DEFAULT);

        assertThat(parsed.helpRequested()).isTrue();
        assertThat(parsed.sources()).containsExactly(DEFAULT);
        assertThat(parsed.explicitConfig()).isFalse();
    }

    @Test
    void rejectsUnknownArgumentsMissingValuesAndConflictingDiagnostics() {
        assertThatThrownBy(() -> ConfigArguments.parse(
                new String[]{"--config"}, DEFAULT))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("路径参数");
        assertThatThrownBy(() -> ConfigArguments.parse(
                new String[]{"--unknown"}, DEFAULT))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("未知参数");
        assertThatThrownBy(() -> ConfigArguments.parse(
                new String[]{"--log-level", "LOUD"}, DEFAULT))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("--log-level");
        assertThatThrownBy(() -> ConfigArguments.parse(new String[]{
                "--check-config", "--print-effective-config"}, DEFAULT))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("不能同时");
    }
}
