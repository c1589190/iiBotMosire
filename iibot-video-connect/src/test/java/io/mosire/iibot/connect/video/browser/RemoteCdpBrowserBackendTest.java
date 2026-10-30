package io.mosire.iibot.connect.video.browser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RemoteCdpBrowserBackendTest {
    @TempDir
    Path temp;

    @Test
    void capturesFakePageThroughLocalCdp() throws Exception {
        Path chromium = Path.of("/snap/bin/chromium");
        assumeTrue(Files.isExecutable(chromium), "system Chromium not installed");

        Process process = new ProcessBuilder(chromium.toString(),
                "--headless=new", "--disable-gpu", "--no-first-run",
                "--disable-dev-shm-usage", "--remote-debugging-port=0",
                "--user-data-dir=" + temp.resolve("chromium-profile"),
                "about:blank")
                .redirectErrorStream(true)
                .start();
        try {
            String endpoint = readCdpEndpoint(process, Duration.ofSeconds(20));
            assumeTrue(endpoint != null, "Chromium did not expose a CDP endpoint");

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/fake", exchange -> {
                byte[] body = ("<html><body style='margin:0;background:#fff'>"
                        + "<div id='frame' style='width:640px;height:360px;"
                        + "background:linear-gradient(135deg,#00aa00,#0000ff);"
                        + "color:white;font-size:48px'>REMOTE FRAME</div>"
                        + "</body></html>").getBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            try {
                int port = server.getAddress().getPort();
                BrowserTarget target = request -> URI.create("http://127.0.0.1:" + port + "/fake");
                Path output = temp.resolve("remote-frame.jpg");
                RemoteCdpBrowserBackend backend = new RemoteCdpBrowserBackend(
                        target, BrowserPolicy.testLoopback(), endpoint, 1);
                try {
                    BrowserFrameRequest request = new BrowserFrameRequest(
                            "BV1xx411c7mD", 1, 1.5, "#frame", output);
                    BrowserProfile profile = BrowserProfile.bilibili(
                            true, 900, 700, 30, temp.resolve("profile"));
                    BrowserFrameResult result = backend.captureFrame(profile, request);
                    assertThat(result.file()).isEqualTo(output);
                    assertThat(result.width()).isEqualTo(640);
                    assertThat(result.height()).isEqualTo(360);
                    assertThat(result.backend()).isEqualTo("playwright-remote-cdp");
                    assertThat(Files.size(output)).isGreaterThan(500);
                    BufferedImage image = ImageIO.read(output.toFile());
                    assertThat(image).isNotNull();
                    assertThat(image.getRGB(10, 10)).isNotEqualTo(image.getRGB(630, 350));
                } finally {
                    backend.close();
                }
            } finally {
                server.stop(0);
            }
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    private static String readCdpEndpoint(Process process, Duration timeout) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try (var reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int index = line.indexOf("DevTools listening on ");
                    if (index >= 0) {
                        return line.substring(index + "DevTools listening on ".length()).trim();
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
            return null;
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
