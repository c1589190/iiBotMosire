package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.MessageSegment;
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

    @Test
    void extractsReadableCardTextWithoutPersistingRawJson() throws Exception {
        String card = """
                {"prompt":"套餐卡片","meta":{"detail_1":{
                  "title":"三国资料包","desc":"包含完整表格",
                  "qqdocurl":"https://example.invalid/card/42"}}}
                """;
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"group","self_id":"10001",
                 "message_id":"g-20","user_id":"20002","group_id":"30003",
                 "message":[{"type":"json","data":{"data":%s}}]}
                """.formatted(mapper.writeValueAsString(card)));

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.text()).isEmpty();
        assertThat(message.nonTextSummary())
                .contains("[卡片×1]", "套餐卡片", "三国资料包", "https://example.invalid/card/42");
        MessageSegment cardSegment = message.segments().getFirst();
        assertThat(cardSegment.type()).isEqualTo(MessageSegment.Type.OTHER);
        assertThat(cardSegment.rawType()).isEqualTo("json");
        assertThat(cardSegment.text())
                .contains("套餐卡片", "三国资料包", "https://example.invalid/card/42")
                .doesNotContain("\"meta\"", "\"prompt\"");
    }

    @Test
    void extractsCardTextFromCqStringWithEscapedCommas() throws Exception {
        String card = """
                {"prompt":"群公告卡片","meta":{"detail_1":{
                  "title":"周末活动","qqdocurl":"https://example.invalid/event"}}}
                """.replace("\n", "");
        String cq = "[CQ:json,data=" + card.replace(",", "&#44;") + "]";
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":21,"user_id":2,"message":%s}
                """.formatted(mapper.writeValueAsString(cq)));

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.nonTextSummary())
                .contains("[卡片×1]", "群公告卡片", "周末活动",
                        "https://example.invalid/event");
        assertThat(message.segments().getFirst().rawType()).isEqualTo("json");
    }

    @Test
    void extractsReadableTextFromXmlCard() throws Exception {
        String xml = "<msg><item><title>XML 活动</title><summary>周末见</summary>"
                + "<url>https://example.invalid/xml-card</url></item></msg>";
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":1,
                 "message_id":22,"user_id":2,
                 "message":[{"type":"xml","data":{"data":%s}}]}
                """.formatted(mapper.writeValueAsString(xml)));

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.nonTextSummary())
                .contains("[卡片×1]", "XML 活动", "周末见",
                        "https://example.invalid/xml-card");
        assertThat(message.segments().getFirst().rawType()).isEqualTo("xml");
    }

    @Test
    void mapsVoiceRecordToAudioMetadataWithoutTranscription() throws Exception {
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"group","self_id":"10001",
                 "message_id":"g-voice","user_id":"20002","group_id":"30003",
                 "message":[{"type":"record","data":{
                   "file":"voice.silk","url":"https://example.invalid/voice.silk",
                   "file_size":"1234"}}]}
                """);

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.text()).isEmpty();
        assertThat(message.nonTextSummary()).isEqualTo("[语音×1]");
        MessageSegment audio = message.segments().getFirst();
        assertThat(audio.type()).isEqualTo(MessageSegment.Type.AUDIO);
        assertThat(audio.fileId()).isEqualTo("voice.silk");
        assertThat(audio.url()).isEqualTo("https://example.invalid/voice.silk");
        assertThat(audio.byteSize()).isEqualTo(1234L);
        assertThat(audio.media()).isTrue();
    }

    @Test
    void mapsGroupFileSegmentsToFileMetadata() throws Exception {
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"group","self_id":"10001",
                 "message_id":"g-22","user_id":"20002","group_id":"30003",
                 "message":[{"type":"file","data":{
                   "file_name":"三国.json","file_id":"file-secret","file_size":"401546"}}]}
                """);

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        assertThat(message.text()).isEmpty();
        assertThat(message.nonTextSummary()).isEqualTo("[文件×1] 三国.json");
        MessageSegment file = message.segments().getFirst();
        assertThat(file.type()).isEqualTo(MessageSegment.Type.FILE);
        assertThat(file.text()).isEqualTo("三国.json");
        assertThat(file.fileId()).isEqualTo("file-secret");
        assertThat(file.byteSize()).isEqualTo(401_546L);
        assertThat(file.media()).isTrue();
        assertThat(file.rawType()).isEqualTo("file");
    }

    @Test
    void mapsCqFileSegmentsToFileMetadata() throws Exception {
        JsonNode event = mapper.readTree("""
                {"post_type":"message","message_type":"group","self_id":"10001",
                 "message_id":"g-23","user_id":"20002","group_id":"30003",
                 "message":"[CQ:file,file_name=三国.json,file_id=file-secret,file_size=401546]"}
                """);

        InboundMessage message = codec.decodeMessage("napcat", event, Instant.EPOCH)
                .orElseThrow();

        MessageSegment file = message.segments().getFirst();
        assertThat(file.type()).isEqualTo(MessageSegment.Type.FILE);
        assertThat(file.text()).isEqualTo("三国.json");
        assertThat(file.fileId()).isEqualTo("file-secret");
        assertThat(file.byteSize()).isEqualTo(401_546L);
        assertThat(message.nonTextSummary()).isEqualTo("[文件×1] 三国.json");
    }

}
