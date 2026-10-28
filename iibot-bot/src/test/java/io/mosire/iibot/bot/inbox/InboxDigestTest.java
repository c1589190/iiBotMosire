package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.summary.MessageSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxDigestTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void digestKeepsAllSummariesAndUsesLatestAsHeaderWithHighestPriority() {
        MessageSummary first = summary(1L, 10, "第一条");
        MessageSummary second = summary(2L, 90, "第二条");

        InboxDigest digest = InboxDigest.of(List.of(first, second));

        assertThat(digest.fromSeq()).isEqualTo(1L);
        assertThat(digest.toSeq()).isEqualTo(2L);
        assertThat(digest.messages()).containsExactly(first, second);
        assertThat(digest.summary()).isEqualTo("第二条");
        assertThat(digest.priority()).isEqualTo(90);
    }

    @Test
    void explicitUnreadRangeCanBeWiderThanPreview() {
        MessageSummary fourth = summary(4L, 0, "第四条");
        MessageSummary fifth = summary(5L, 0, "第五条");

        InboxDigest digest = InboxDigest.of(1L, 5L, List.of(fourth, fifth),
                List.of(), "第五条", "SOURCE_WEIGHT",
                io.mosire.iibot.bot.weight.InboxWeight.none(0),
                new InboxSource("thread:test", "group", "group-9", "群聊"),
                new InboxTrigger("m-5", 5L, "qq-1", "Alice", "", "第五条",
                        "SOURCE_WEIGHT"),
                5, true);

        assertThat(digest.fromSeq()).isEqualTo(1L);
        assertThat(digest.toSeq()).isEqualTo(5L);
        assertThat(digest.messages()).extracting(MessageSummary::localSeq)
                .containsExactly(4L, 5L);
        assertThat(digest.unreadCount()).isEqualTo(5);
        assertThat(digest.hasMoreUnread()).isTrue();
    }

    @Test
    void rejectsMixedThreadsAndOutOfOrderSeq() {
        MessageSummary first = summary(2L, 0, "第二条");
        MessageSummary second = summary(1L, 0, "第一条");

        assertThatThrownBy(() -> InboxDigest.of(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("升序");

        MessageSummary otherThread = new MessageSummary("thread:other", 3L, "m-3",
                ChatDirection.IN, "qq-1", "Alice", Set.of(), "", 0, "别的来源", T0);
        assertThatThrownBy(() -> InboxDigest.of(List.of(second, otherThread)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该 thread");
    }

    private static MessageSummary summary(long seq, int priority, String text) {
        return new MessageSummary("thread:test", seq, "m-" + seq, ChatDirection.IN,
                "qq-1", "Alice", Set.of(), "", priority, text, T0);
    }
}
