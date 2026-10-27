package io.mosire.iibot.app;

import io.mosire.iibot.app.config.ConfigException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainModeTest {

    @Test
    void botIsDefaultModeWithoutModeFlag() {
        assertThat(Main.parseMode(new String[0])).isEqualTo("bot");
        assertThat(Main.parseMode(new String[]{"--config", "a.json"})).isEqualTo("bot");
    }

    @Test
    void legacyAndExplicitBotRemainSupported() {
        assertThat(Main.parseMode(new String[]{"--mode", "legacy"})).isEqualTo("legacy");
        assertThat(Main.parseMode(new String[]{"--mode=bot"})).isEqualTo("bot");
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> Main.parseMode(new String[]{"--mode", "game"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("bot 或 legacy");
    }
}
