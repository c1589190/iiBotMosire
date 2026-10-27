package io.mosire.iibot.bot.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSourceKeyTest {

    @Test
    void sameTargetAndTypeProduceTheSameOpaqueThreadId() {
        ChatSourceKey first = ChatSourceKey.groupChat("bot-1", "group-9");
        ChatSourceKey second = ChatSourceKey.groupChat("bot-1", "group-9");

        assertThat(first.threadId()).isEqualTo(second.threadId());
        assertThat(first.threadId()).startsWith("thread:group:");
        assertThat(first.threadId()).doesNotContain("group-9");
    }

    @Test
    void privateAndGroupWithTheSameTargetStaySeparate() {
        ChatSourceKey privateSource = ChatSourceKey.privateChat("bot-1", "123");
        ChatSourceKey groupSource = ChatSourceKey.groupChat("bot-1", "123");

        assertThat(privateSource.threadId()).isNotEqualTo(groupSource.threadId());
    }

    @Test
    void accountIsPartOfTheSourceIdentity() {
        ChatSourceKey first = ChatSourceKey.groupChat("bot-1", "group-9");
        ChatSourceKey second = ChatSourceKey.groupChat("bot-2", "group-9");

        assertThat(first.threadId()).isNotEqualTo(second.threadId());
    }

    @Test
    void blankFieldsAreRejected() {
        assertThatThrownBy(() -> ChatSourceKey.privateChat("", "123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatSourceKey.groupChat("bot-1", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
