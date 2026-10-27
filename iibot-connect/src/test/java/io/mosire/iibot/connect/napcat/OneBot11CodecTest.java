package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OneBot11CodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OneBot11Codec codec = new OneBot11Codec();

    @Test
    void parsesPrivateArrayEventAndConcatenatesTextSegments() throws Exception {
        JsonNode event = mapper.readTree("""
                {
                  "time": 1700000000,
                  "self_id": 10001,
                  "post_type": "message",
                  "message_type": "private",
                  "message_id": 42,
                  "user_id": 20002,
                  "sender": {"nickname": "Alice"},
                  "message": [
                    {"type": "text", "data": {"text": "Hello "}},
                    {"type": "image", "data": {"file": "ignored.png"}},
                    {"type": "text", "data": {"text": "world"}}
                  ],
                  "unknown_future_field": {"nested": true}
                }
                """);

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH).orElseThrow();

        assertThat(message.adapterId()).isEqualTo("napcat");
        assertThat(message.selfId()).isEqualTo("10001");
        assertThat(message.messageId()).isEqualTo("42");
        assertThat(message.userId()).isEqualTo("20002");
        assertThat(message.senderName()).isEqualTo("Alice");
        assertThat(message.sessionType()).isEqualTo(SessionType.PRIVATE);
        assertThat(message.groupId()).isNull();
        assertThat(message.text()).isEqualTo("Hello world");
        assertThat(message.nonTextSummary()).isEqualTo("[图片×1]");
        assertThat(message.segments()).extracting(segment -> segment.type().name())
                .containsExactly("TEXT", "IMAGE", "TEXT");
        assertThat(message.replyToMessageId()).isEmpty();
        assertThat(message.mentionedSelf()).isFalse();
        assertThat(message.receivedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(message.dedupeKey()).isEqualTo("self:10001:private:msg:42");
    }

    @Test
    void parsesGroupCqStringAndDecodesEscapedBrackets() throws Exception {
        JsonNode event = mapper.readTree("""
                {
                  "time": 1700000001,
                  "self_id": "10001",
                  "post_type": "message",
                  "message_type": "group",
                  "message_id": "g-7",
                  "user_id": "20002",
                  "group_id": "30003",
                  "sender": {"card": "群名片A"},
                  "message": "[CQ:image,file=x.png]群友&#91;A&#93;[CQ:face,id=1] 你好"
                }
                """);

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH).orElseThrow();

        assertThat(message.sessionType()).isEqualTo(SessionType.GROUP);
        assertThat(message.groupId()).isEqualTo("30003");
        assertThat(message.senderName()).isEqualTo("群名片A");
        assertThat(message.text()).isEqualTo("群友[A] 你好");
        assertThat(message.nonTextSummary()).isEqualTo("[图片×1][表情×1]");
        assertThat(message.dedupeKey())
                .isEqualTo("self:10001:group:30003:msg:g-7");
    }

    @Test
    void keepsNonTextOnlyMessagesAsPlaceholders() throws Exception {
        JsonNode imageOnly = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":2,"user_id":3,
                 "message":[{"type":"image","data":{"file":"x.png"}}]}
                """);

        InboundMessage message = codec.decodeMessage("napcat", imageOnly, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.text()).isEmpty();
        assertThat(message.nonTextSummary()).isEqualTo("[图片×1]");
        assertThat(message.senderName()).isEqualTo("3");
    }

    @Test
    void parsesSenderCardMentionAndReplyReferences() throws Exception {
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"group","self_id":"10001",
                 "message_id":"g-8","user_id":"20002","group_id":"30003",
                 "sender":{"card":"Alice","nickname":"alice"},
                 "message":[
                   {"type":"reply","data":{"id":"999"}},
                   {"type":"at","data":{"qq":"10001"}},
                   {"type":"text","data":{"text":" 在吗"}}
                 ]}
                """);

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.senderName()).isEqualTo("Alice");
        assertThat(message.text()).isEqualTo(" 在吗");
        assertThat(message.nonTextSummary()).isEqualTo("[引用×1][@×1]");
        assertThat(message.replyToMessageId()).isEqualTo("999");
        assertThat(message.optionalReplyToMessageId()).contains("999");
        assertThat(message.mentionedSelf()).isTrue();
    }

    @Test
    void ignoresUnsupportedEventsAndEmptyMessages() throws Exception {
        JsonNode notice = mapper.readTree("""
                {"post_type":"notice","notice_type":"group_increase"}
                """);
        JsonNode request = mapper.readTree("""
                {"post_type":"request","request_type":"friend"}
                """);
        JsonNode meta = mapper.readTree("""
                {"post_type":"meta_event","meta_event_type":"heartbeat"}
                """);
        JsonNode unknownMessageType = mapper.readTree("""
                {"post_type":"message","message_type":"guild","self_id":1,
                 "message_id":2,"user_id":3,"message":"hello"}
                """);
        JsonNode emptyMessage = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":2,"user_id":3,"message":[]}
                """);

        assertThat(codec.decodeMessage("napcat", notice, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", request, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", meta, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", unknownMessageType, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", emptyMessage, Instant.EPOCH)).isEmpty();
    }

    @Test
    void isNullSafeForMissingMalformedAndNullInputs() throws Exception {
        JsonNode missingIdentity = mapper.readTree("""
                {"post_type":"message","message_type":"private","message":[
                  {"type":"text","data":{"text":"hello"}}
                ]}
                """);
        JsonNode nullMessage = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":2,"user_id":3,"message":null}
                """);
        JsonNode textDataNull = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":2,"user_id":3,
                 "message":[{"type":"text","data":{"text":null}}, null,
                            {"type":"text","data":null}]}
                """);

        assertThat(codec.decodeMessage(null, nullMessage, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", null, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", nullMessage, null)).isEmpty();
        assertThat(codec.decodeMessage("napcat", missingIdentity, Instant.EPOCH)).isEmpty();
        assertThat(codec.decodeMessage("napcat", textDataNull, Instant.EPOCH)).isEmpty();
    }

    @Test
    void fallsBackToReceiveTimeWhenTimestampIsInvalid() throws Exception {
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":2,"user_id":3,"time":-5,
                 "message":"plain text"}
                """);
        Instant fallback = Instant.parse("2026-01-02T03:04:05Z");

        InboundMessage message = codec.decodeMessage("napcat", event, fallback).orElseThrow();

        assertThat(message.text()).isEqualTo("plain text");
        assertThat(message.senderName()).isEqualTo("3");
        assertThat(message.receivedAt()).isEqualTo(fallback);
    }
    @Test
    void parsesFriendRequestIntoLocalEvent() throws Exception {
        JsonNode event = mapper.readTree("""
                {"post_type":"request","request_type":"friend","time":1700000002,
                 "self_id":10001,"user_id":20002,"flag":"request-flag",
                 "comment":"想加好友","nickname":"Alice"}
                """);

        var request = codec.decodeFriendRequest(event, Instant.EPOCH).orElseThrow();

        assertThat(request.flag()).isEqualTo("request-flag");
        assertThat(request.userId()).isEqualTo("20002");
        assertThat(request.nickname()).isEqualTo("Alice");
        assertThat(request.comment()).isEqualTo("想加好友");
        assertThat(request.receivedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_002L));
    }

}
