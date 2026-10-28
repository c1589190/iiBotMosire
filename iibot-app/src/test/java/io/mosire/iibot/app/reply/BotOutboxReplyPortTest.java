package io.mosire.iibot.app.reply;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotOutboxReplyPortTest {

    @Test
    void sendsReplyAndMentionSegmentsInRichGroupReply() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        BotOutboxReplyPort port = new BotOutboxReplyPort(
                new OutboxSender(adapter, Duration.ofSeconds(2), null, Duration.ZERO));

        BotReplySendResult result = port.sendWithMention(
                ChatSourceKey.groupChat("bot-1", "group-9"),
                "群回复", "m-1", "qq-1", "r-rich");

        assertThat(result.state()).isEqualTo(DeliveryState.SENT);
        OutboundMessage sent = adapter.sent().getFirst();
        assertThat(sent.richSegments()).containsExactly(
                new OutboundSegment.Reply("m-1"),
                new OutboundSegment.Mention("qq-1"),
                new OutboundSegment.Text("群回复"));
    }

    @Test
    void routesPrivateAndGroupRepliesBySourceWithoutArbitraryTarget() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        BotOutboxReplyPort port = new BotOutboxReplyPort(
                new OutboxSender(adapter, Duration.ofSeconds(2), null, Duration.ZERO));

        BotReplySendResult privateResult = port.send(
                ChatSourceKey.privateChat("bot-1", "qq-1"), "私聊回复", "", "r-1");
        BotReplySendResult groupResult = port.send(
                ChatSourceKey.groupChat("bot-1", "group-9"), "群回复", "", "r-2");

        assertThat(privateResult.state()).isEqualTo(DeliveryState.SENT);
        assertThat(groupResult.state()).isEqualTo(DeliveryState.SENT);
        assertThat(adapter.sent()).hasSize(2);
        assertThat(adapter.sent().get(0).targetType())
                .isEqualTo(OutboundMessage.TargetType.PRIVATE);
        assertThat(adapter.sent().get(0).targetId()).isEqualTo("qq-1");
        assertThat(adapter.sent().get(1).targetType())
                .isEqualTo(OutboundMessage.TargetType.GROUP);
        assertThat(adapter.sent().get(1).targetId()).isEqualTo("group-9");
    }
    @Test
    void routesEachReplyToSenderForSourceAccount() {
        FakeMessagingAdapter adapterA = new FakeMessagingAdapter("adapter-a");
        FakeMessagingAdapter adapterB = new FakeMessagingAdapter("adapter-b");
        BotOutboxReplyPort port = new BotOutboxReplyPort(Map.of(
                "bot-a", new OutboxSender(adapterA, Duration.ofSeconds(2), "temp-a",
                        Duration.ZERO),
                "bot-b", new OutboxSender(adapterB, Duration.ofSeconds(2), "temp-b",
                        Duration.ZERO)));

        BotReplySendResult a = port.send(
                ChatSourceKey.groupChat("bot-a", "group-a"), "回复 A", "", "r-a");
        BotReplySendResult b = port.send(
                ChatSourceKey.privateChat("bot-b", "qq-b"), "回复 B", "", "r-b");

        assertThat(a.state()).isEqualTo(DeliveryState.SENT);
        assertThat(b.state()).isEqualTo(DeliveryState.SENT);
        assertThat(adapterA.sent()).hasSize(1);
        assertThat(adapterA.sent().getFirst().targetId()).isEqualTo("group-a");
        assertThat(adapterB.sent()).hasSize(1);
        assertThat(adapterB.sent().getFirst().targetId()).isEqualTo("qq-b");
    }

    @Test
    void missingAccountFailsBeforeSendAndNeverUsesAnotherAdapter() {
        FakeMessagingAdapter adapterA = new FakeMessagingAdapter("adapter-a");
        BotOutboxReplyPort port = new BotOutboxReplyPort(Map.of(
                "bot-a", new OutboxSender(adapterA, Duration.ofSeconds(2), null,
                        Duration.ZERO)));

        BotReplySendResult result = port.send(
                ChatSourceKey.groupChat("bot-missing", "group-a"), "不能发送", "", "r-x");

        assertThat(result.state()).isEqualTo(DeliveryState.FAILED_BEFORE_SEND);
        assertThat(result.errorCode()).isEqualTo("ACCOUNT_UNAVAILABLE");
        assertThat(adapterA.sent()).isEmpty();
    }

}
