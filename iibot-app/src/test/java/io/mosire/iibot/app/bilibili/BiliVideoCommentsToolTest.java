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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BiliVideoCommentsToolTest {

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
    void readsTopLevelCommentsWithCursor() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":100,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        route("/x/v2/reply", """
                {"code":0,"message":"0","data":{
                  "replies":[
                    {"rpid_str":"1001","root_str":"1001","parent_str":"1001",
                     "member":{"mid":42,"uname":"用户"},"content":{"message":"好看"},
                     "like":7,"ctime":1700000000,"state":0}
                  ],
                  "page":{"num":1,"size":10,"count":2,"acount":21}
                }}
                """);
        BiliVideoCommentsTool tool = new BiliVideoCommentsTool(base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "limit", 10, "sort", "hot"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("comments")).hasSize(1);
        assertThat(data.path("comments").get(0).path("rpid").asText())
                .isEqualTo("1001");
        assertThat(data.path("comments").get(0).path("message").asText())
                .isEqualTo("好看");
        assertThat(data.path("nextCursor").asText()).isEqualTo("2");
        assertThat(data.path("hasMore").asBoolean()).isTrue();
        assertThat(data.path("untrustedContent").asBoolean()).isTrue();
    }

    @Test
    void readsNestedRepliesForRootRpid() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":100,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        route("/x/v2/reply/reply", """
                {"code":0,"message":"0","data":{
                  "replies":[
                    {"rpid_str":"1002","root_str":"1001","parent_str":"1001",
                     "member":{"mid":43,"uname":"回复者"},"content":{"message":"同感"},
                     "like":2,"ctime":1700000001,"state":0}
                  ],
                  "page":{"num":1,"size":10,"count":1,"acount":1}
                }}
                """);
        BiliVideoCommentsTool tool = new BiliVideoCommentsTool(base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "root", "1001"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("comments")).hasSize(1);
        assertThat(data.path("comments").get(0).path("rootRpid").asText())
                .isEqualTo("1001");
        assertThat(data.path("rootRpid").asText()).isEqualTo("1001");
    }

    @Test
    void rejectsInvalidCursor() {
        BiliVideoCommentsTool tool = new BiliVideoCommentsTool(base, base);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "cursor", "not-a-number"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
    }

    private static ToolResult execute(BiliVideoCommentsTool tool, Map<String, Object> args) {
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
