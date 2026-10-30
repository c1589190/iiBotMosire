package io.mosire.iibot.app.bilibili;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.iibot.connect.bilibili.BilibiliCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BiliLoginServiceTest {

    @TempDir
    Path tempDir;
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
    void writesOwnerOnlyCredentialAfterQrSuccess() throws Exception {
        route("/x/passport-login/web/qrcode/generate", """
                {"code":0,"data":{"url":"https://example.invalid/qr","qrcode_key":"key-1"}}
                """, null);
        route("/x/passport-login/web/qrcode/poll", """
                {"code":0,"data":{"code":0,"message":"登录成功"}}
                """, exchange -> {
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "SESSDATA=sess-value; Path=/");
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "bili_jct=csrf-value; Path=/");
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "DedeUserID=123456; Path=/");
                });
        Path credentialFile = tempDir.resolve("secrets/bili.json");
        BiliVideoReadTool.BiliVideoAccount account =
                new BiliVideoReadTool.BiliVideoAccount("bili-123456", credentialFile);
        BiliLoginService service = new BiliLoginService(List.of(account), base);

        BiliLoginService.StartResult start = service.start("");
        assertThat(start.qrUrl()).isEqualTo("https://example.invalid/qr");
        BiliLoginService.PollResult poll = service.poll(start.loginId());

        assertThat(poll.state()).isEqualTo("SUCCESS");
        assertThat(Files.isRegularFile(credentialFile)).isTrue();
        BilibiliCredential credential = BilibiliCredential.load(credentialFile);
        assertThat(credential.sessdata()).isEqualTo("sess-value");
        assertThat(credential.biliJct()).isEqualTo("csrf-value");
        assertThat(Files.getPosixFilePermissions(credentialFile))
                .doesNotContain(java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
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
        void handle(HttpExchange exchange) throws IOException;
    }
}
