package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NapCatConfigTest {

    @Test
    void redactsTokenAndQueryStringFromToString() {
        NapCatConfig config = NapCatConfig.builder(
                        URI.create("ws://127.0.0.1:3001/?access_token=super-secret"),
                        URI.create("http://127.0.0.1:3000/"))
                .accessToken("super-secret")
                .accountId("10001")
                .connectTimeout(Duration.ofMillis(250))
                .requestTimeout(Duration.ofMillis(300))
                .rateLimitPerSecond(1_000)
                .rateLimitBurst(1_000)
                .build();

        assertThat(config.toString())
                .doesNotContain("super-secret")
                .contains("accessToken=***")
                .contains("?<redacted>");
        assertThat(config.accessToken()).contains("super-secret");
    }

    @Test
    void rejectsInvalidConfigurationValues() {
        URI ws = URI.create("ws://127.0.0.1:3001/");
        URI http = URI.create("http://127.0.0.1:3000/");

        assertThatThrownBy(() -> NapCatConfig.builder(URI.create("http://x/"), http).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NapCatConfig.builder(ws, URI.create("ws://x/")).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NapCatConfig.builder(ws, http).maxTextLength(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NapCatConfig.builder(ws, http)
                        .initialReconnectDelay(Duration.ofSeconds(2))
                        .maxReconnectDelay(Duration.ofSeconds(1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NapCatConfig.builder(ws, http).reconnectJitter(1.5).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
