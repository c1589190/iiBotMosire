package io.mosire.iibot.connect.video.bilibili;

import com.sun.net.httpserver.HttpServer;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliAdapterTest {

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
    void pollsDmIntoExternalInboundMessageAndAcksIt() throws Exception {
        route("/x/web-interface/nav", navJson());
        route("/session_svr/v1/session_svr/new_sessions", """
                {"code":0,"data":{"ack_ts":1800000000000000,"session_list":[{
                  "talker_id":98765,"session_type":1,"unread_count":1,
                  "ack_seqno":40,"max_seqno":41,"session_ts":1799999999000000
                }]}}
                """);
        route("/svr_sync/v1/svr_sync/fetch_session_msgs", """
                {"code":0,"data":{"messages":[{
                  "sender_uid":98765,"receiver_id":123456,"receiver_type":1,
                  "msg_type":1,"content":"{\\"content\\":\\"你好\\"}",
                  "msg_seqno":41,"timestamp":1799999999,
                  "msg_key_str":"9007199254740993","msg_status":0
                }]}}
                """);
        route("/session_svr/v1/session_svr/update_ack", """
                {"code":0,"message":"0","data":{}}
                """);
        AtomicReference<InboundMessage> captured = new AtomicReference<>();
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base);
             BilibiliAdapter adapter = new BilibiliAdapter(config(true), client)) {
            var sessions = client.newSessions(0L).sessions();
            assertThat(sessions).hasSize(1);
            assertThat(sessions.getFirst().unreadCount()).isEqualTo(1L);
            assertThat(sessions.getFirst().ackSeqno()).isEqualTo(40L);
            var messages = client.fetchSessionMessages(98765L, 1, 40L, 50).messages();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().msgSeqno()).isEqualTo(41L);
            adapter.bindInboundHandlerForTest(captured::set);
            adapter.pollPrivateMessages();
            InboundMessage message = captured.get();
            assertThat(message).isNotNull();
            assertThat(message.sessionType()).isEqualTo(InboundMessage.SessionType.EXTERNAL);
            assertThat(message.sourceAddress())
                    .isEqualTo(MessageSourceAddress.biliDm("bili-123456", "98765"));
            assertThat(message.text()).isEqualTo("你好");
            assertThat(message.messageId()).isEqualTo("9007199254740993");
            assertThat(message.replyToMessageId()).isEmpty();
        }
    }

    @Test
    void pollsCommentNotificationIntoCommentThread() throws Exception {
        route("/x/web-interface/nav", navJson());
        route("/x/msgfeed/reply", """
                {"code":0,"data":{"items":[{
                  "id":100,
                  "user":{"mid":42,"nickname":"回复者"},
                  "item":{"subject_id":117461382864435,"business_id":1,
                    "root_id":1001,"source_id":1002,"target_id":1001,
                    "source_content":"谢谢","target_reply_content":"原评论",
                    "title":"测试视频"},
                  "reply_time":1799999999
                }]}}
                """);
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":10,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        AtomicReference<InboundMessage> captured = new AtomicReference<>();
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base);
             BilibiliAdapter adapter = new BilibiliAdapter(config(true), client)) {
            adapter.bindInboundHandlerForTest(captured::set);
            adapter.setCommentCursorForTest(0L);
            adapter.pollCommentReplies();
            InboundMessage message = captured.get();
            assertThat(message).isNotNull();
            assertThat(message.sessionType()).isEqualTo(InboundMessage.SessionType.EXTERNAL);
            assertThat(message.sourceAddress()).isEqualTo(
                    MessageSourceAddress.biliComment("bili-123456",
                            "BV1EPnG6DEJC", "1001"));
            assertThat(message.messageId()).isEqualTo("1002");
            assertThat(message.replyToMessageId()).isEqualTo("1001");
            assertThat(message.text()).isEqualTo("谢谢");
        }
    }

    @Test
    void sendsCommentReplyThroughCommentThreadSource() throws Exception {
        route("/x/web-interface/nav", navJson());
        route("/x/web-interface/view", """
                {"code":0,"message":"0","data":{
                  "bvid":"BV1EPnG6DEJC","aid":117461382864435,
                  "cid":42838132685,"title":"测试视频","desc":"",
                  "duration":10,"pages":[{"cid":42838132685,"page":1}],
                  "owner":{"mid":1,"name":"UP"}
                }}
                """);
        route("/x/v2/reply/add", """
                {"code":0,"message":"0","data":{"rpid_str":"1003"}}
                """);
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base);
             BilibiliAdapter adapter = new BilibiliAdapter(config(true), client)) {
            MessageSourceAddress source = MessageSourceAddress.biliComment(
                    "bili-123456", "BV1EPnG6DEJC", "1001");
            OutboundMessage message = OutboundMessage.richExternalMessage(source,
                    List.of(new OutboundSegment.Reply("1002"),
                            new OutboundSegment.Text("回复")), "comment-test-1");

            SendReceipt receipt = adapter.send(message).toCompletableFuture().get();
            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(receipt.adapterMessageIds()).containsExactly("1003");
        }
    }

    @Test
    void sendsDmThroughGenericOutboundMessage() throws Exception {
        route("/x/web-interface/nav", navJson());
        route("/web_im/v1/web_im/send_msg", """
                {"code":0,"message":"0","data":{"msg_key_str":"9007199254740994"}}
                """);
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base);
             BilibiliAdapter adapter = new BilibiliAdapter(config(true), client)) {
            MessageSourceAddress source = MessageSourceAddress.biliDm(
                    "bili-123456", "98765");
            OutboundMessage message = OutboundMessage.richExternalMessage(
                    source, List.of(new OutboundSegment.Text("回复")), "dm-test-1");

            SendReceipt receipt = adapter.send(message).toCompletableFuture().get();
            assertThat(receipt.state()).isEqualTo(SendReceipt.State.SENT);
            assertThat(receipt.adapterMessageIds())
                    .containsExactly("9007199254740994");
        }
    }

    @Test
    void writeDisabledFailsBeforeCallingBilibili() throws Exception {
        try (BilibiliHttpClient client = new BilibiliHttpClient(
                BilibiliCredential.anonymous(), "test-agent", base, base);
             BilibiliAdapter adapter = new BilibiliAdapter(config(false), client)) {
            MessageSourceAddress source = MessageSourceAddress.biliDm(
                    "bili-123456", "98765");
            OutboundMessage message = OutboundMessage.richExternalMessage(
                    source, List.of(new OutboundSegment.Text("回复")), "dm-test-2");

            SendReceipt receipt = adapter.send(message).toCompletableFuture().get();
            assertThat(receipt.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(receipt.errorCode()).isEqualTo("BILI_WRITE_DISABLED");
        }
    }

    private static BilibiliAdapterConfig config(boolean writeEnabled) {
        return new BilibiliAdapterConfig("bili-123456", Path.of("/tmp/unused.json"),
                Duration.ofSeconds(30), true, true, writeEnabled, 5);
    }

    private static String navJson() {
        return """
                {"code":0,"data":{"isLogin":true,"mid":123456,"uname":"Bot",
                  "wbi_img":{
                    "img_url":"https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
                    "sub_url":"https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"}}}
                """;
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
