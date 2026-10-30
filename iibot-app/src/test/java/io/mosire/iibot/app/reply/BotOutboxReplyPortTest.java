package io.mosire.iibot.app.reply;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotOutboxReplyPortTest {

    @TempDir
    Path tempDir;

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
    void sendsExplicitMentionAllSegmentInGroupReply() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        BotOutboxReplyPort port = new BotOutboxReplyPort(
                new OutboxSender(adapter, Duration.ofSeconds(2), null, Duration.ZERO));

        BotReplySendResult result = port.sendWithMentions(
                ChatSourceKey.groupChat("bot-1", "group-9"),
                "全员通知", null, "", true, "r-all");

        assertThat(result.state()).isEqualTo(DeliveryState.SENT);
        assertThat(adapter.sent().getFirst().richSegments()).containsExactly(
                new OutboundSegment.MentionAll(),
                new OutboundSegment.Text("全员通知"));
    }

    @Test
    void resolvesUploadedImageAndMapsStickerIntoRichGroupMessage() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        BotMediaStore store = new BotMediaStore(tempDir);
        String assetRef = store.storeUpload(new MediaAsset(
                "image.png", "image/png", new byte[]{1, 2, 3}));
        BotOutboxReplyPort port = new BotOutboxReplyPort(
                new OutboxSender(adapter, Duration.ofSeconds(2), null, Duration.ZERO),
                store);

        BotReplySendResult result = port.sendRich(
                ChatSourceKey.groupChat("bot-1", "group-9"),
                "看图", null, "", false,
                List.of(new OutboundSegment.Image(assetRef, "image.png", "image/png"),
                        new OutboundSegment.Sticker("1")),
                "rich-image-1");

        assertThat(result.state()).isEqualTo(DeliveryState.SENT);
        List<OutboundSegment> segments = adapter.sent().getFirst().richSegments();
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0)).isEqualTo(new OutboundSegment.Text("看图"));
        OutboundSegment.Image image = (OutboundSegment.Image) segments.get(1);
        assertThat(image.assetRef()).isEqualTo(assetRef);
        assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(segments.get(2)).isEqualTo(new OutboundSegment.Sticker("1"));
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


    @Test
    void routesExternalBilibiliSourceAsGenericOutbound() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        BotOutboxReplyPort port = new BotOutboxReplyPort(
                new OutboxSender(adapter, Duration.ofSeconds(2), null, Duration.ZERO));
        MessageSourceAddress source = MessageSourceAddress.biliDm(
                "bili-123456", "98765");

        BotReplySendResult result = port.send(
                ChatSourceKey.fromSourceAddress(source), "B站回复", "", "r-bili");

        assertThat(result.state()).isEqualTo(DeliveryState.SENT);
        OutboundMessage sent = adapter.sent().getFirst();
        assertThat(sent.targetType()).isEqualTo(OutboundMessage.TargetType.EXTERNAL);
        assertThat(sent.sourceAddress()).isEqualTo(source);
        assertThat(sent.richSegments()).containsExactly(
                new OutboundSegment.Text("B站回复"));
    }
}
