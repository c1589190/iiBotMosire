package io.mosire.iibot.app.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigBrowserTest {

    @Test
    void defaultsAreDisabledEmbeddedAndBounded() {
        BotConfig.Browser browser = BotConfig.Browser.defaults();

        assertThat(browser.enabled()).isFalse();
        assertThat(browser.mode()).isEqualTo("embedded");
        assertThat(browser.headless()).isTrue();
        assertThat(browser.maxConcurrentSessions()).isEqualTo(2);
        assertThat(browser.timeoutSeconds()).isEqualTo(60);
        assertThat(browser.viewportWidth()).isEqualTo(1280);
        assertThat(browser.viewportHeight()).isEqualTo(720);
    }

    @Test
    void remoteCdpRequiresEndpoint() {
        assertThatThrownBy(() -> new BotConfig.Browser(true, "remote-cdp", "",
                "", true, 2, 60, "", 1280, 720))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cdpEndpoint");
    }

    @Test
    void executablePathMustBeAbsolute() {
        assertThatThrownBy(() -> new BotConfig.Browser(true, "embedded",
                "bin/chromium", "", true, 2, 60, "", 1280, 720))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executablePath");
    }

    @Test
    void rejectsUnsafeViewportAndTimeout() {
        assertThatThrownBy(() -> new BotConfig.Browser(true, "embedded", "",
                "", true, 2, 4, "", 1280, 720))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(() -> new BotConfig.Browser(true, "embedded", "",
                "", true, 2, 60, "", 200, 720))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("viewportWidth");
    }
}
