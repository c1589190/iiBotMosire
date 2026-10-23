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
