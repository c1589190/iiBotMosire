package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BiliVideoReadToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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
    void readsAnyBilibiliUrlWithoutInboxOrMessageSource() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"测试简介",
                  "duration":1248,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":3546775719774786,"name":"UP主"}
                }}
                """);
        route("/x/v2/reply", """
                {"code":0,"message":"0","data":{"replies":[
                  {"rpid_str":"1001","root_str":"1001","parent_str":"1001",
                   "member":{"mid":42,"uname":"用户"},"content":{"message":"好看"},
                   "like":7,"ctime":1700000000,"state":0}
                ]}}
                """);
        BiliVideoReadTool tool = new BiliVideoReadTool(List.of(), base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "https://www.bilibili.com/video/BV1EPnG6DEJC",
                "includeComments", true,
                "includeTranscript", false));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("video").path("bvid").asText()).isEqualTo("BV1EPnG6DEJC");
        assertThat(data.path("video").path("title").asText()).isEqualTo("测试视频");
        assertThat(data.path("description").asText()).isEqualTo("测试简介");
        assertThat(data.path("comments")).hasSize(1);
        assertThat(data.path("comments").get(0).path("text").asText()).isEqualTo("好看");
        assertThat(data.path("untrustedContent").asBoolean()).isTrue();
    }

    @Test
    void rejectsArbitraryHostsAndLocalFiles() {
        BiliVideoReadTool tool = new BiliVideoReadTool(List.of(), base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "file:///tmp/BV1EPnG6DEJC"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_TARGET");
    }

    private static ToolResult execute(BiliVideoReadTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
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
}
