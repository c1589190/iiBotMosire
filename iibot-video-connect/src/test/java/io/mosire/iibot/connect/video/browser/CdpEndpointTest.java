package io.mosire.iibot.connect.video.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdpEndpointTest {

    @Test
    void acceptsLoopbackHttpWebSocketAndIpv6() {
        assertThat(CdpEndpoint.parse("http://127.0.0.1:9222").uri().getPort()).isEqualTo(9222);
        assertThat(CdpEndpoint.parse("ws://localhost:9222/devtools/browser/x").uri().getScheme())
                .isEqualTo("ws");
        assertThat(CdpEndpoint.parse("http://[::1]:9222").uri().getHost()).isEqualTo("[::1]");
    }

    @Test
    void rejectsRemoteHostAndCredentials() {
        assertThatThrownBy(() -> CdpEndpoint.parse("http://example.com:9222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回环");
        assertThatThrownBy(() -> CdpEndpoint.parse("http://user:pass@127.0.0.1:9222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
        assertThatThrownBy(() -> CdpEndpoint.parse("ftp://127.0.0.1:9222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https/ws/wss");
        assertThatThrownBy(() -> CdpEndpoint.parse("http://[bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合法 URI");
    }
}
