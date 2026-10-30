package io.mosire.iibot.connect.bilibili;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliLoginClientTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void startsQrAndReturnsCredentialFromPollHeaders() throws Exception {
        route("/x/passport-login/web/qrcode/generate", """
                {"code":0,"data":{"url":"https://example.invalid/qr","qrcode_key":"key-1"}}
                """, null);
        route("/x/passport-login/web/qrcode/poll", """
                {"code":0,"data":{"code":0,"message":"登录成功"}}
                """, exchange -> {
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "SESSDATA=sess-value; Path=/; HttpOnly");
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "bili_jct=csrf-value; Path=/");
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "DedeUserID=123456; Path=/");
                });
        try (BilibiliLoginClient client = new BilibiliLoginClient(base)) {
            BilibiliLoginClient.QrStart qr = client.start();
            assertThat(qr.qrcodeKey()).isEqualTo("key-1");
            BilibiliLoginClient.QrPoll poll = client.poll(qr.qrcodeKey());
            assertThat(poll.state()).isEqualTo(BilibiliLoginClient.QrState.SUCCESS);
            assertThat(poll.credential()).isNotNull();
            assertThat(poll.credential().sessdata()).isEqualTo("sess-value");
            assertThat(poll.credential().biliJct()).isEqualTo("csrf-value");
            assertThat(poll.credential().dedeUserId()).isEqualTo("123456");
            assertThat(poll.credential().toString()).doesNotContain("sess-value");
        }
    }

    @Test
    void mapsWaitingAndExpiredStates() throws Exception {
        route("/x/passport-login/web/qrcode/generate", """
                {"code":0,"data":{"url":"https://example.invalid/qr","qrcode_key":"key-2"}}
                """, null);
        route("/x/passport-login/web/qrcode/poll", """
                {"code":0,"data":{"code":86101,"message":"未扫码"}}
                """, null);
        try (BilibiliLoginClient client = new BilibiliLoginClient(base)) {
            assertThat(client.poll("key-2").state())
                    .isEqualTo(BilibiliLoginClient.QrState.WAITING_SCAN);
        }
    }

    private void route(String path, String body, Handler handler) throws IOException {
        server.createContext(path, exchange -> {
            try {
                if (handler != null) handler.handle(exchange);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
    }

    @FunctionalInterface
    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
