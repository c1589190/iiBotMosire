package io.mosire.iibot.bot.summary;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.note.UserNote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSummaryBuilderTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @Test
    void summaryContainsSenderTagsAndBoundedBody() {
        ChatMessage message = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "这是一条很长的消息".repeat(80), "", "");
        ChatMessage stored = message.withLocalSeq(7L);
        UserNote note = new UserNote("bot-1", "qq-1", "Alice", Set.of("vip", "friend"),
                "重要联系人", 88, 1L, T0);
        MessageSummaryBuilder builder = new MessageSummaryBuilder(64);

        MessageSummary summary = builder.build(stored, note);

        assertThat(summary.summary().length()).isLessThanOrEqualTo(64);
        assertThat(summary.summary()).contains("#vip", "#friend", "Alice", "重要联系人");
        assertThat(summary.priority()).isEqualTo(88);
        assertThat(summary.localSeq()).isEqualTo(7L);
    }

    @Test
    void nonTextAndOutboundMessagesStillGetAReadableSummary() {
        ChatMessage inbound = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "", "[图片×1]", "").withLocalSeq(1L);
        ChatMessage outbound = ChatMessage.outbound(GROUP, "reply-1", T0.plusSeconds(1),
                "收到", "", "").withLocalSeq(2L);
        MessageSummaryBuilder builder = new MessageSummaryBuilder();

        assertThat(builder.build(inbound, null).summary()).contains("Alice", "[图片×1]");
        assertThat(builder.build(outbound, null).summary()).contains("我", "收到");
    }
}
