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
import io.mosire.iibot.connect.video.browser.BrowserBackend;
import io.mosire.iibot.connect.video.browser.BrowserFrameRequest;
import io.mosire.iibot.connect.video.browser.BrowserFrameResult;
import io.mosire.iibot.connect.video.browser.BrowserProfile;
import io.mosire.iibot.connect.video.browser.BrowserSession;
import io.mosire.iibot.connect.video.core.VideoQuality;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void extractsExplicitTimesAndReportsQuality() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":100,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        route("/x/player/playurl", """
                {"code":0,"data":{"dash":{"video":[
                  {"baseUrl":"__VIDEO_URL__","backupUrl":[],
                   "width":1280,"height":720,"codecs":"avc1","bandwidth":1000},
                  {"baseUrl":"__VIDEO_URL__","backupUrl":[],
                   "width":640,"height":360,"codecs":"avc1","bandwidth":400}
                ]}}}
                """.replace("__VIDEO_URL__", base + "/video.mp4"));
        routeBytes("/video.mp4", new byte[]{0, 1, 2, 3, 4});
        BotMediaStore store = new BotMediaStore(tempDir);
        RecordingExtractor extractor = new RecordingExtractor();
        BiliVideoFramesTool tool = new BiliVideoFramesTool(store, extractor, base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC",
                "times", List.of("0:01", "0:02.5", "0:03")));

        assertThat(result.success()).isTrue();
        assertThat(extractor.timestamps).containsExactly(1.0, 2.5, 3.0);
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("streamHeight").asInt()).isEqualTo(720);
        assertThat(data.path("requestedQuality").asText()).isEqualTo("auto");
        assertThat(data.path("actualQuality").asText()).isEqualTo("720p");
        assertThat(data.path("availableQualities")).hasSize(2);
        assertThat(data.path("qualityFallback").asBoolean()).isFalse();
    }

    @Test
    void browserFallbackCapturesWhenFfmpegFails() throws Exception {
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
        AtomicInteger captures = new AtomicInteger();
        BrowserBackend backend = new BrowserBackend() {
            @Override
            public BrowserSession open(BrowserProfile profile) {
                return new BrowserSession() {
                    @Override
                    public BrowserFrameResult capture(BrowserFrameRequest request) {
                        captures.incrementAndGet();
                        try {
                            Files.write(request.outputFile(), new byte[]{(byte) 0xFF,
                                    (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                        return new BrowserFrameResult(request.outputFile(), 640, 360,
                                "fake-browser");
                    }

                    @Override public void close() { }
                };
            }

            @Override public void close() { }
        };
        BrowserProfile profile = BrowserProfile.bilibili(true, 1280, 720, 30,
                tempDir.resolve("browser-profile"));
        BiliVideoFramesTool tool = new BiliVideoFramesTool(store, new FailingExtractor(),
                base, base, BiliVideoFramesTool.NAME, VideoQuality.AUTO, 720, 6, 9,
                backend, profile);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "count", 2));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).hasSize(2);
        assertThat(captures).hasValue(2);
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("frames")).hasSize(2);
        tool.close();
    }

    @Test
    void rejectsCountTogetherWithExplicitTime() {
        BotMediaStore store = new BotMediaStore(tempDir);
        BiliVideoFramesTool tool = new BiliVideoFramesTool(
                store, new FakeExtractor(), base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "count", 2, "time", "0:01"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_TIMESTAMPS");
    }

    @Test
    void rejectsTimestampBeyondPageDuration() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":100,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        BotMediaStore store = new BotMediaStore(tempDir);
        BiliVideoFramesTool tool = new BiliVideoFramesTool(
                store, new FakeExtractor(), base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "time", "2:00"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_TIMESTAMPS");
    }

    @Test
    void strictQualityFailsWhenRequestedHeightIsUnavailable() throws Exception {
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
        BotMediaStore store = new BotMediaStore(tempDir);
        BiliVideoFramesTool tool = new BiliVideoFramesTool(
                store, new FakeExtractor(), base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "quality", "1080p", "strictQuality", true));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("QUALITY_UNAVAILABLE");
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

    private static final class RecordingExtractor implements BiliFrameExtractor {
        private final List<Double> timestamps = new ArrayList<>();

        @Override
        public Frame extract(Path videoFile, double timestampSeconds,
                             Path outputFile) throws Exception {
            timestamps.add(timestampSeconds);
            Files.write(outputFile, new byte[]{(byte) 0xFF, (byte) 0xD8,
                    (byte) 0xFF, (byte) 0xD9});
            return new Frame(outputFile, 1280, 720);
        }
    }

    private static final class FailingExtractor implements BiliFrameExtractor {
        @Override
        public Frame extract(Path videoFile, double timestampSeconds,
                             Path outputFile) throws Exception {
            throw new IOException("ffmpeg unavailable");
        }
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
