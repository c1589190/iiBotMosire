package io.mosire.iibot.app.delivery;

import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.agent.InteractionReply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxSenderTest {

    @Test
    void sendsPrivateRepliesWithStableKeysAndOriginalUserTarget() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));
        var inbound = TestSupport.privateMessage("qq-p1", "hello", "dedupe-1");
        List<InteractionReply> replies = List.of(new InteractionReply("reply-a"),
                new InteractionReply("reply-b"));

        List<SendOutcome> first = outbox.sendReplies(inbound, replies);
        List<SendOutcome> second = outbox.sendReplies(inbound, replies);

        assertThat(first).extracting(SendOutcome::state)
                .containsExactly(SendReceipt.State.SENT, SendReceipt.State.SENT);
        assertThat(first).extracting(SendOutcome::idempotencyKey)
                .containsExactly("inbound:dedupe-1:reply:0", "inbound:dedupe-1:reply:1");
        assertThat(second).extracting(SendOutcome::idempotencyKey)
                .isEqualTo(first.stream().map(SendOutcome::idempotencyKey).toList());
        assertThat(adapter.sent()).hasSize(4);
        assertThat(adapter.sent()).allSatisfy(message -> {
            assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
            assertThat(message.targetId()).isEqualTo("qq-p1");
        });
        assertThat(adapter.sent().get(0).textSegments()).containsExactly("reply-a");
    }

    @Test
    void sanitizesMarkdownAndBackstageFieldsAtFinalSendBoundary() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));
        String raw = """
                ## 当前局势
                你的位置在卡普亚北隘。
                draftId: 11111111-1111-1111-1111-111111111111
                请打开 player/registration-form-v2.md 阅读。
                **请发送** `/确认 K7Q2M9`
                """;

        outbox.sendReplies(TestSupport.privateMessage("qq-p1", "hello", "dedupe-safe"),
                List.of(new InteractionReply(raw)));

        assertThat(adapter.sent()).hasSize(1);
        String sent = adapter.sent().get(0).textSegments().get(0);
        assertThat(sent)
                .contains("当前局势", "你的位置在卡普亚北隘", "/确认 K7Q2M9")
                .doesNotContain("##", "**", "draftId", "11111111",
                        "registration-form-v2.md", "player/");
    }

    @Test
    void publicBroadcastAlsoStripsBackstageAndMarkdown() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));

        outbox.broadcastToPublicGroup("group-public", List.of("""
                **世界公告**
                城外的车队开始入城。
                ticketId: 11111111-1111-1111-1111-111111111111
                """), "public-safe");

        assertThat(adapter.sent()).hasSize(1);
        String sent = adapter.sent().get(0).textSegments().get(0);
        assertThat(sent).contains("世界公告", "城外的车队开始入城")
                .doesNotContain("**", "ticketId", "11111111");
    }

    @Test
    void privateTemporaryGroupIsCarriedIntoOutboundMessage() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2),
                "1104560326");

        outbox.sendPrivateToUser("qq-p4", List.of("hello"), "temp-group-test");

        assertThat(adapter.sent()).hasSize(1);
        assertThat(adapter.sent().get(0).temporaryGroupId()).isEqualTo("1104560326");
    }

    @Test
    void explicitTemporaryGroupOverridesConfiguredDefault() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2),
                "configured-group");

        outbox.sendPrivateToUser("qq-new", "source-group", List.of("报名卡"),
                "onboarding-source-group");

        assertThat(adapter.sent()).singleElement().satisfies(message -> {
            assertThat(message.targetId()).isEqualTo("qq-new");
            assertThat(message.temporaryGroupId()).isEqualTo("source-group");
            assertThat(message.textSegments()).containsExactly("报名卡");
        });
    }

    @Test
    void minimumSendIntervalSpacesConsecutiveLogicalMessages() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2),
                null, Duration.ofMillis(150));

        long start = System.nanoTime();
        outbox.sendPrivateToUser("qq-p1", List.of("first"), "paced-1");
        outbox.sendPrivateToUser("qq-p1", List.of("second"), "paced-2");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(adapter.sent()).hasSize(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(120L);
    }

    @Test
    void failedBeforeSendIsRecordedAndNotRetried() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(SendReceipt.failed("NAP", "server rejected")));
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));

        List<SendOutcome> outcomes = outbox.sendReplies(
                TestSupport.privateMessage("qq-p1", "x", "dedupe-fail"),
                List.of(new InteractionReply("reply")));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
        assertThat(outcomes.get(0).errorCode()).isEqualTo("NAP");
        assertThat(adapter.sent()).as("outbox 不自动重发").hasSize(1);
    }

    @Test
    void unknownIsRecordedAndNeverResent() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(SendReceipt.unknown("TIMEOUT", "no ack")));
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));

        List<SendOutcome> outcomes = outbox.sendReplies(
                TestSupport.privateMessage("qq-p1", "x", "dedupe-unknown"),
                List.of(new InteractionReply("reply")));

        assertThat(outcomes.get(0).state()).isEqualTo(SendReceipt.State.UNKNOWN);
        assertThat(outcomes.get(0).unknown()).isTrue();
        assertThat(adapter.sent()).as("UNKNOWN 严禁自动重发").hasSize(1);
    }

    @Test
    void boundedWaitMapsTimeoutToUnknown() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(
                message -> new CompletableFuture<>());
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofMillis(80));

        List<SendOutcome> outcomes = outbox.sendReplies(
                TestSupport.privateMessage("qq-p1", "x", "dedupe-timeout"),
                List.of(new InteractionReply("reply")));

        assertThat(outcomes.get(0).state()).isEqualTo(SendReceipt.State.UNKNOWN);
        assertThat(outcomes.get(0).errorCode()).isEqualTo("SEND_TIMEOUT");
        assertThat(adapter.sent()).hasSize(1);
    }
}
