package io.mosire.iibot.bot.summary;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.note.UserNote;
import org.junit.jupiter.api.Test;

import io.mosire.iibot.connect.core.MessageSegment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
    void linesLeadWithShortTimeAndLocalSeq() {
        ChatMessage stored = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                Instant.parse("2026-10-26T14:21:00Z"), T0, "开会吗", "", "").withLocalSeq(9646L);
        MessageSummaryBuilder builder = new MessageSummaryBuilder(200, ZoneId.of("Asia/Shanghai"),
                Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC));

        String rendered = builder.build(stored, null).summary();

        assertThat(rendered).startsWith("[22:21] #9646 Alice：");
        assertThat(rendered).doesNotContain("2026-10-26 22:21");
    }

    @Test
    void olderMessagesCarryTheDateSoYesterdaysLinesAreUnambiguous() {
        ChatMessage stored = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                Instant.parse("2026-10-25T14:21:00Z"), T0, "昨天说的", "", "").withLocalSeq(1L);
        MessageSummaryBuilder builder = new MessageSummaryBuilder(200, ZoneId.of("Asia/Shanghai"),
                Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC));

        assertThat(builder.build(stored, null).summary()).startsWith("[10-25 22:21] #1 Alice：");
    }

    @Test
    void mentionsNameTheirTargetInsteadOfCountingThem() {
        ChatMessage stored = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "看下这个", "", "").withLocalSeq(1L);
        MessageSummaryBuilder builder = new MessageSummaryBuilder(200);
        List<MessageSegment> segments = List.of(
                MessageSegment.mention(0, "bot-1"),
                MessageSegment.mention(1, "3924945183"));

        String rendered = builder.build(stored, null, null, segments).summary();

        assertThat(rendered).contains("[@你] [@3924945183]");
        assertThat(rendered).doesNotContain("[@×2]");
    }

    @Test
    void noteIsRenderedOnlyForTheFirstOccurrenceInWindow() {
        ChatMessage stored = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "在吗", "", "").withLocalSeq(1L);
        UserNote note = new UserNote("bot-1", "qq-1", "Alice", Set.of(), "主人", 500, 1L, T0);
        MessageSummaryBuilder builder = new MessageSummaryBuilder(200);

        assertThat(builder.build(stored, note, null, List.of(), false).summary())
                .doesNotContain("主人");
        assertThat(builder.build(stored, note, null, List.of(), true).summary())
                .contains("备注：主人");
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

    @Test
    void mediaMessagesCarryTheBoardKeySoAgentsCanFetchTheImage() {
        ChatMessage inbound = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "", "[图片×2]", "").withLocalSeq(1L);
        MessageSummaryBuilder builder = new MessageSummaryBuilder(200);

        MessageSummary summary = builder.build(inbound, null);

        assertThat(summary.messageKey()).isEqualTo("in:thread:group:Ym90LTE:Z3JvdXAtOQ:m-1");
        assertThat(summary.summary())
                .contains("[图片×2 messageKey=" + summary.messageKey() + "]");
        assertThat(new MessageSummaryBuilder(64).build(inbound, null).summary().length())
                .isLessThanOrEqualTo(64);
    }

    @Test
    void textOnlyMessagesDoNotCarryABoardKeyHint() {
        ChatMessage inbound = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "纯文本", "", "").withLocalSeq(1L);

        MessageSummary summary = new MessageSummaryBuilder().build(inbound, null);

        assertThat(summary.messageKey()).isNotEmpty();
        assertThat(summary.summary()).doesNotContain("messageKey=");
    }
}
