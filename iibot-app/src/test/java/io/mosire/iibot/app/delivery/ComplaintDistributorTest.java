package io.mosire.iibot.app.delivery;

import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintDeliveryState;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStatus;
import io.mosire.iibot.taskflow.complaint.InMemoryComplaintStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ComplaintDistributorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);
    private static final String RESPONSE = "GM 回应私聊正文-6e6f";

    @Test
    void respondedPendingComplaintIsSentToPlayerPrivateChatAndMarkedSent() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        String complaintId = submitAndRespond(service, PLAYER);
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));
        ComplaintDistributor distributor = new ComplaintDistributor(
                service, outbox, Map.of("player-1", "qq-p1"));

        List<SendOutcome> outcomes = distributor.distributeIfReady();

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.sent()).isTrue();
            assertThat(outcome.idempotencyKey())
                    .isEqualTo("private:complaint:" + complaintId + ":response:reply:0");
        });
        assertThat(adapter.sent()).singleElement().satisfies(message -> {
            assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
            assertThat(message.targetId()).isEqualTo("qq-p1");
            assertThat(message.textSegments()).containsExactly(RESPONSE);
            assertThat(message.idempotencyKey())
                    .isEqualTo("private:complaint:" + complaintId + ":response:reply:0");
        });
        Complaint complaint = service.getForGm(complaintId).orElseThrow();
        assertThat(complaint.deliveryState()).isEqualTo(ComplaintDeliveryState.SENT);
    }

    @Test
    void failedBeforeSendMarksFailedAndUnknownMarksUnknown() {
        ComplaintService failedService = new ComplaintService(
                new InMemoryComplaintStore(), CLOCK);
        String failedId = submitAndRespond(failedService, PLAYER);
        FakeMessagingAdapter failedAdapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(
                        SendReceipt.failed("ADAPTER_REJECTED", "拒绝")));
        new ComplaintDistributor(failedService,
                new OutboxSender(failedAdapter, Duration.ofSeconds(2)),
                Map.of("player-1", "qq-p1")).distributeIfReady();
        assertThat(failedService.getForGm(failedId).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.FAILED);

        ComplaintService unknownService = new ComplaintService(
                new InMemoryComplaintStore(), CLOCK);
        String unknownId = submitAndRespond(unknownService, PLAYER);
        FakeMessagingAdapter unknownAdapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(
                        SendReceipt.unknown("ADAPTER_TIMEOUT", "超时")));
        new ComplaintDistributor(unknownService,
                new OutboxSender(unknownAdapter, Duration.ofSeconds(2)),
                Map.of("player-1", "qq-p1")).distributeIfReady();
        assertThat(unknownService.getForGm(unknownId).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.UNKNOWN);
    }

    @Test
    void unknownIsNotAutomaticallyResentAndInProcessAttemptedSetPreventsDuplicates() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        String complaintId = submitAndRespond(service, PLAYER);
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(
                        SendReceipt.unknown("ADAPTER_TIMEOUT", "超时")));
        ComplaintDistributor distributor = new ComplaintDistributor(
                service, new OutboxSender(adapter, Duration.ofSeconds(2)),
                Map.of("player-1", "qq-p1"));

        assertThat(distributor.distributeIfReady()).hasSize(1);
        assertThat(distributor.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(1);
        assertThat(service.getForGm(complaintId).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.UNKNOWN);
    }

    @Test
    void unknownPlayerIsMarkedFailedSafelyWithoutSendingOrThrowing() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        ComplaintCommandResult submitted = service.submit(PLAYER, "标题", "正文", "submit-1");
        assertThat(service.respond(GM, submitted.complaintId(), 1, RESPONSE, "respond-1").ok())
                .isTrue();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        ComplaintDistributor distributor = new ComplaintDistributor(
                service, new OutboxSender(adapter, Duration.ofSeconds(2)), Map.of());

        List<SendOutcome> outcomes = distributor.distributeIfReady();

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(outcome.errorCode()).isEqualTo("UNKNOWN_PLAYER");
        });
        assertThat(adapter.sent()).isEmpty();
        assertThat(service.getForGm(submitted.complaintId()).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.FAILED);
    }

    @Test
    void submittedComplaintIsIgnored() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        service.submit(PLAYER, "未回应标题", "未回应正文", "submit-only");
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        ComplaintDistributor distributor = new ComplaintDistributor(
                service, new OutboxSender(adapter, Duration.ofSeconds(2)),
                Map.of("player-1", "qq-p1"));

        assertThat(distributor.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).isEmpty();
        Complaint complaint = service.listForGm(ComplaintStatus.SUBMITTED).getFirst();
        assertThat(complaint.deliveryState()).isNull();
    }

    private static String submitAndRespond(ComplaintService service, Actor player) {
        ComplaintCommandResult submitted = service.submit(player, "投诉标题",
                "投诉正文-不应进入日志", "submit-" + player.actorId());
        assertThat(submitted.ok()).isTrue();
        ComplaintCommandResult responded = service.respond(
                GM, submitted.complaintId(), 1, RESPONSE, "respond-" + player.actorId());
        assertThat(responded.ok()).isTrue();
        return submitted.complaintId();
    }
}
