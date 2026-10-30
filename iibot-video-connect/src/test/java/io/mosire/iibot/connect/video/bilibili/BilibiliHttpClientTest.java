package io.mosire.iibot.connect.video.bilibili;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BilibiliHttpClientTest {

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
    void readsVideoAndCommentsFromPublicEndpoints() throws Exception {
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"简介",
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
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base)) {
            BilibiliModels.VideoInfo video = client.video("BV1EPnG6DEJC", null, 1);
            assertThat(video.title()).isEqualTo("测试视频");
            assertThat(video.aid()).isEqualTo(117461382864435L);
            assertThat(video.cid()).isEqualTo(42838132685L);

            List<BilibiliModels.Comment> comments = client.comments(
                    Long.toString(video.aid()), 1, 10);
            assertThat(comments).hasSize(1);
            assertThat(comments.getFirst().message()).isEqualTo("好看");
            assertThat(comments.getFirst().rootRpid()).isEqualTo("1001");
        }
    }

    @Test
    void pollsAndSendsPrivateMessagesWithWbiSignature() throws Exception {
        AtomicReference<String> sendQuery = new AtomicReference<>();
        route("/x/web-interface/nav", """
                {"code":0,"message":"0","data":{
                  "isLogin":true,"mid":123456,"uname":"Bot",
                  "wbi_img":{
                    "img_url":"https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
                    "sub_url":"https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
                  }
                }}
                """);
        route("/session_svr/v1/session_svr/new_sessions", """
                {"code":0,"data":{
                  "ack_ts":1800000000000000,
                  "session_list":[{
                    "talker_id":98765,"session_type":1,"unread_count":1,
                    "ack_seqno":40,"max_seqno":41,"session_ts":1799999999000000
                  }]
                }}
                """);
        route("/svr_sync/v1/svr_sync/fetch_session_msgs", """
                {"code":0,"data":{"messages":[
                  {"sender_uid":98765,"receiver_id":123456,"receiver_type":1,
                   "msg_type":1,"content":"{\\"content\\":\\"你好\\"}",
                   "msg_seqno":41,"timestamp":1799999999,
                   "msg_key_str":"9007199254740993","msg_status":0}
                ]}}
                """);
        route("/session_svr/v1/session_svr/update_ack", """
                {"code":0,"message":"0","data":{}}
                """);
        route("/web_im/v1/web_im/send_msg", """
                {"code":0,"message":"0","data":{"msg_key_str":"9007199254740994"}}
                """, exchange -> sendQuery.set(exchange.getRequestURI().getQuery()));

        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base)) {
            BilibiliModels.AccountInfo account = client.validateAccount();
            assertThat(account.mid()).isEqualTo(123456L);
            BilibiliModels.SessionPage sessions = client.newSessions(1L);
            assertThat(sessions.sessions()).hasSize(1);
            assertThat(sessions.nextBeginTsMicros()).isEqualTo(1800000000000000L);

            BilibiliModels.MessagePage page = client.fetchSessionMessages(
                    98765L, 1, 40L, 20);
            assertThat(page.messages()).hasSize(1);
            assertThat(page.messages().getFirst().msgKey())
                    .isEqualTo("9007199254740993");

            client.updateAck(98765L, 1, 41L);
            BilibiliModels.SendResult result = client.sendPrivateMessage(98765L, "你好");
            assertThat(result.accepted()).isTrue();
            assertThat(result.msgKey()).isEqualTo("9007199254740994");
            assertThat(sendQuery.get()).contains("w_rid=").contains("wts=");
        }
    }

    @Test
    void readsCommentNotificationsAndPostsReply() throws Exception {
        route("/x/web-interface/nav", """
                {"code":0,"data":{"isLogin":true,"mid":123456,"uname":"Bot",
                  "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
                  "sub_url":"https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"}}}
                """);
        route("/x/msgfeed/reply", """
                {"code":0,"data":{"items":[{
                  "id":823260581625886,
                  "user":{"mid":42,"nickname":"回复者"},
                  "item":{"subject_id":117461382864435,"business_id":1,
                    "root_id":1001,"source_id":1002,"target_id":1001,
                    "source_content":"谢谢","target_reply_content":"原评论",
                    "title":"测试视频"},
                  "reply_time":1799999999
                }]}}
                """);
        route("/x/v2/reply/add", """
                {"code":0,"message":"0","data":{"rpid_str":"1003"}}
                """);
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base)) {
            List<BilibiliModels.CommentNotification> notifications =
                    client.commentNotifications(20);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().rootRpid()).isEqualTo("1001");
            assertThat(notifications.getFirst().sourceRpid()).isEqualTo("1002");
            assertThat(notifications.getFirst().message()).isEqualTo("谢谢");

            BilibiliModels.SendResult result = client.replyToComment(
                    "117461382864435", "1001", "1002", "回复");
            assertThat(result.accepted()).isTrue();
            assertThat(result.msgKey()).isEqualTo("1003");
        }
    }

    @Test
    void rejectsNonBilibiliTargets() {
        assertThat(BilibiliTarget.parse("BV1EPnG6DEJC").bvid())
                .isEqualTo("BV1EPnG6DEJC");
        assertThat(BilibiliTarget.parse("av117461382864435").aid())
                .isEqualTo("117461382864435");
        assertThat(BilibiliTarget.parse(
                "https://www.bilibili.com/video/BV1EPnG6DEJC?p=2").page())
                .isEqualTo(2);
        assertThat(new BilibiliTargetResolver().resolve("BV1EPnG6DEJC").bvid())
                .isEqualTo("BV1EPnG6DEJC");
        assertThatThrownBy(() -> BilibiliTarget.parse(
                "https://evil.example/video/BV1EPnG6DEJC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BilibiliTarget.parse(
                "file:///tmp/BV1EPnG6DEJC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void route(String path, String body) throws IOException {
        route(path, body, exchange -> { });
    }

    private void route(String path, String body, Handler handler) throws IOException {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
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
