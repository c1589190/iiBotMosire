package io.mosire.iibot.connect.video.browser;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserGuardTest {
    private final BrowserGuard guard = new BrowserGuard(BrowserPolicy.bilibili());

    @Test
    void allowsBilibiliOnly() {
        assertThat(guard.validate("https://www.bilibili.com/video/BV1xx411c7mD"))
                .isEqualTo(URI.create("https://www.bilibili.com/video/BV1xx411c7mD"));
        assertThat(guard.validate("https://passport.bilibili.com/login")).isNotNull();
    }

    @Test
    void rejectsDangerousSchemesAndForeignHosts() {
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .isInstanceOf(BrowserException.class)
                .extracting("code").isEqualTo("BROWSER_SCHEME_DENIED");
        assertThatThrownBy(() -> guard.validate("data:text/html,hello"))
                .isInstanceOf(BrowserException.class)
                .extracting("code").isEqualTo("BROWSER_SCHEME_DENIED");
        assertThatThrownBy(() -> guard.validate("javascript:alert(1)"))
                .isInstanceOf(BrowserException.class)
                .extracting("code").isEqualTo("BROWSER_SCHEME_DENIED");
        assertThatThrownBy(() -> guard.validate("https://evil.example/video"))
                .isInstanceOf(BrowserException.class)
                .extracting("code").isEqualTo("BROWSER_DOMAIN_DENIED");
    }

    @Test
    void loopbackPolicyIsOptInForTests() {
        BrowserGuard testGuard = new BrowserGuard(BrowserPolicy.testLoopback());
        assertThat(testGuard.validate("http://127.0.0.1:1234/fake")).isNotNull();
        assertThatThrownBy(() -> guard.validate("http://127.0.0.1:1234/fake"))
                .isInstanceOf(BrowserException.class)
                .extracting("code").isEqualTo("BROWSER_DOMAIN_DENIED");
    }
}
