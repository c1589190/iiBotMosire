package io.mosire.iibot.bot.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageSourceAddressTest {

    @Test
    void qqSourcesRoundTripAndExposeBusinessParts() {
        MessageSourceAddress group = MessageSourceAddress.qqGroup("10001", "955432692");
        MessageSourceAddress parsedGroup = MessageSourceAddress.parse(group.canonical());

        assertThat(group.canonical()).isEqualTo("qq_group/10001/955432692");
        assertThat(parsedGroup).isEqualTo(group);
        assertThat(parsedGroup.accountId()).isEqualTo("10001");
        assertThat(parsedGroup.targetId()).isEqualTo("955432692");
        assertThat(parsedGroup.toChatSourceKey())
                .isEqualTo(ChatSourceKey.groupChat("10001", "955432692"));

        MessageSourceAddress privateChat =
                MessageSourceAddress.qqPrivate("10001", "123456789");
        assertThat(privateChat.canonical()).isEqualTo("qq_private/10001/123456789");
        assertThat(MessageSourceAddress.parse(privateChat.canonical()))
                .isEqualTo(privateChat);
        assertThat(privateChat.toChatSourceKey())
                .isEqualTo(ChatSourceKey.privateChat("10001", "123456789"));
    }

    @Test
    void appAndQqSystemSourcesAreDistinct() {
        MessageSourceAddress app = MessageSourceAddress.appSystem("runtime", "started");
        MessageSourceAddress qq = MessageSourceAddress.qqSystem("10001", "friend_request");

        assertThat(app.canonical()).isEqualTo("app_system/runtime/started");
        assertThat(app.eventPath()).containsExactly("runtime", "started");
        assertThat(app.accountId()).isEmpty();
        assertThat(qq.canonical()).isEqualTo("qq_system/10001/friend_request");
        assertThat(qq.accountId()).isEqualTo("10001");
        assertThat(qq.eventPath()).containsExactly("friend_request");
        assertThatThrownBy(app::toChatSourceKey)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentsArePercentEncodedAndParsedBack() {
        MessageSourceAddress address = MessageSourceAddress.qqGroup(
                "account with space", "group/with/slash");
        String canonical = address.canonical();

        assertThat(canonical).startsWith("qq_group/account%20with%20space/");
        assertThat(MessageSourceAddress.parse(canonical)).isEqualTo(address);
    }

    @Test
    void futureFamiliesUseTheSameGrammar() {
        MessageSourceAddress future = MessageSourceAddress.parse(
                "wechat_group/account-1/group-2");
        assertThat(future.family()).isEqualTo("wechat");
        assertThat(future.kind()).isEqualTo("group");
        assertThat(future.accountId()).isEqualTo("account-1");
        assertThat(future.targetId()).isEqualTo("group-2");
    }

    @Test
    void legacyThreadIdDecodesToSource() {
        ChatSourceKey key = ChatSourceKey.groupChat("10001", "955432692");
        MessageSourceAddress address = MessageSourceAddress.fromLegacyThreadId(key.threadId());

        assertThat(address).isEqualTo(key.sourceAddress());
        assertThat(ChatSourceKey.fromSourceAddress(address)).isEqualTo(key);
        assertThat(ChatSourceKey.fromThreadId(key.threadId())).isEqualTo(key);
    }

    @Test
    void malformedSourcesFailClosed() {
        assertThatThrownBy(() -> MessageSourceAddress.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSourceAddress.parse("qq_group/account"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSourceAddress.parse(
                "qq_group/account/group/extra"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSourceAddress.parse("qq_group//group"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSourceAddress.parse("qq_group/account/%ZZ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSourceAddress.parse("app_system"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MessageSourceAddress.parse("QQ_GROUP/account/group").canonical())
                .isEqualTo("qq_group/account/group");
    }
}
