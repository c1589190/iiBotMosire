package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.connect.video.bilibili.BilibiliModels;
import io.mosire.iibot.connect.video.frame.BiliFrameExtractor;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BiliVideoFramesToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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
    void returnsUniformFramesAsImageAssets() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":100,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        route("/x/player/playurl", """
                {"code":0,"data":{"dash":{"video":[{
                  "baseUrl":"__VIDEO_URL__","backupUrl":[],
                  "width":1280,"height":720,"codecs":"avc1","bandwidth":1000
                }]}}}
                """.replace("__VIDEO_URL__", base + "/video.mp4"));
        routeBytes("/video.mp4", new byte[]{0, 1, 2, 3, 4});
        BotMediaStore store = new BotMediaStore(tempDir);
        BiliVideoFramesTool tool = new BiliVideoFramesTool(
                store, new FakeExtractor(), base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "count", 3));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).hasSize(3);
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("frames")).hasSize(3);
        assertThat(data.path("frames").get(0).path("timestampSeconds").asDouble())
                .isEqualTo(5.0);
        assertThat(data.path("streamHeight").asInt()).isEqualTo(720);
    }

    private static ToolResult execute(BiliVideoFramesTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }

    private void routeBytes(String path, byte[] body) throws IOException {
        server.createContext(path, exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "video/mp4");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
    }

    private void route(String path, String body) throws IOException {
        server.createContext(path, exchange -> {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
    }

    private static final class FakeExtractor implements BiliFrameExtractor {
        @Override
        public Frame extract(Path videoFile, double timestampSeconds,
                             Path outputFile) throws Exception {
            Files.writeString(videoFile.resolveSibling("seen.txt"),
                    Long.toString(Files.size(videoFile)));
            Files.write(outputFile, new byte[]{(byte) 0xFF, (byte) 0xD8,
                    (byte) 0xFF, (byte) 0xD9});
            return new Frame(outputFile, 1280, 720);
        }
    }
}
