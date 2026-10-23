package io.mosire.iibot.taskflow.complaint;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 投诉领域状态机、隔离、幂等、审计与 copy-on-write 持久化语义。 */
class ComplaintServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_A = new Actor("player-a", ActorRole.PLAYER);
    private static final Actor PLAYER_B = new Actor("player-b", ActorRole.PLAYER);
    private static final Actor SECRETARY = new Actor("secretary-a", ActorRole.SECRETARY);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);
    private static final Actor DIRECTOR = new Actor("director-1", ActorRole.DIRECTOR);
    private static final Actor SUPERVISOR = new Actor("supervisor-1", ActorRole.SUPERVISOR);

    @Test
    void submitWorksWithoutEngineOrTurnAndStartsSubmittedRevisionOne() {
        ComplaintService service = service();
        ComplaintCommandResult result = service.submit(PLAYER_A, "回合外投诉",
                "冻结时也应能提交的正文", "submit-1");

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo(ComplaintService.OK);
        assertThat(result.complaintId()).isNotBlank();
        assertThat(result.revision()).isEqualTo(1);
        Complaint complaint = service.getForGm(result.complaintId()).orElseThrow();
        assertThat(complaint.playerId()).isEqualTo("player-a");
        assertThat(complaint.status()).isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(complaint.revision()).isEqualTo(1);
        assertThat(complaint.deliveryState()).isNull();
        assertThat(complaint.responseBody()).isNull();
        assertThat(service.snapshot().size()).isEqualTo(1);
        assertThat(service.auditLog()).singleElement()
                .extracting(ComplaintAuditEntry::action)
                .isEqualTo(ComplaintAuditAction.SUBMIT);
    }

    @Test
    void duplicateSubmitKeyReturnsFirstResultAndCreatesOnlyOneComplaint() {
        ComplaintService service = service();
        ComplaintCommandResult first = service.submit(PLAYER_A, "标题", "正文", "same-key");
        ComplaintSnapshot afterFirst = service.snapshot();

        ComplaintCommandResult second = service.submit(PLAYER_A, "另一个标题",
                "另一份正文", "same-key");

        assertThat(second).isEqualTo(first);
        assertThat(service.snapshot()).isSameAs(afterFirst);
        assertThat(service.listForGm(null)).hasSize(1);
        assertThat(service.auditLog()).hasSize(1);
    }

    @Test
    void secretaryOnlySeesOwnComplaintsWhileGmCanSeeAndFilterAll() {
        ComplaintService service = service();
        ComplaintCommandResult a = service.submit(PLAYER_A, "A 投诉", "A 正文", "a-submit");
        ComplaintCommandResult b = service.submit(PLAYER_B, "B 投诉", "B 正文", "b-submit");

        assertThat(service.listForSecretary("player-a"))
                .extracting(Complaint::complaintId).containsExactly(a.complaintId());
        assertThat(service.listForSecretary("player-b"))
                .extracting(Complaint::complaintId).containsExactly(b.complaintId());
        assertThat(service.getForSecretary("player-a", b.complaintId())).isEmpty();
        assertThat(service.getForSecretary("player-a", "missing")).isEmpty();
        assertThat(service.getForSecretary("player-a", a.complaintId())).isPresent();

        assertThat(service.listForGm(null)).hasSize(2);
        assertThat(service.listForGm(ComplaintStatus.SUBMITTED)).hasSize(2);
        assertThat(service.getForGm(a.complaintId())).isPresent();
        assertThat(service.getForGm(b.complaintId())).isPresent();
        assertThat(service.listForSecretary(" ")).isEmpty();
    }

    @Test
    void playerAndSecretaryCannotRespondAndFailureHasNoSideEffects() {
        ComplaintService service = service();
        ComplaintCommandResult submitted = service.submit(PLAYER_A, "标题", "正文", "s1");
        ComplaintSnapshot before = service.snapshot();

        ComplaintCommandResult byPlayer = service.respond(PLAYER_A, submitted.complaintId(),
                1, "回应", "r-player");
        ComplaintCommandResult bySecretary = service.respond(SECRETARY, submitted.complaintId(),
                1, "回应", "r-secretary");

        assertThat(byPlayer.code()).isEqualTo(ComplaintService.FORBIDDEN);
        assertThat(bySecretary.code()).isEqualTo(ComplaintService.FORBIDDEN);
        assertThat(service.snapshot()).isSameAs(before);
        assertThat(service.getForGm(submitted.complaintId()).orElseThrow().status())
                .isEqualTo(ComplaintStatus.SUBMITTED);
    }

    @Test
    void gmResponseMovesToRespondedIncrementsRevisionAndSetsDeliveryPending() {
        ComplaintService service = service();
        ComplaintCommandResult submitted = service.submit(PLAYER_A, "标题", "正文", "s1");

        ComplaintCommandResult response = service.respond(GM, submitted.complaintId(),
                1, "GM 的私密回应", "r1");

        assertThat(response.ok()).isTrue();
        assertThat(response.revision()).isEqualTo(2);
        Complaint complaint = service.getForGm(submitted.complaintId()).orElseThrow();
        assertThat(complaint.status()).isEqualTo(ComplaintStatus.RESPONDED);
        assertThat(complaint.revision()).isEqualTo(2);
        assertThat(complaint.respondedBy()).isEqualTo("gm-1");
        assertThat(complaint.responseBody()).isEqualTo("GM 的私密回应");
        assertThat(complaint.respondedAt()).isNotNull();
        assertThat(complaint.deliveryState()).isEqualTo(ComplaintDeliveryState.PENDING);
        assertThat(service.auditLog()).extracting(ComplaintAuditEntry::action)
                .containsExactly(ComplaintAuditAction.SUBMIT, ComplaintAuditAction.RESPOND);
    }

    @Test
    void directorAndSupervisorCanRespondButWrongRevisionStatusOrBlankResponseDoNotMutate() {
        ComplaintService service = service();
        ComplaintCommandResult directed = service.submit(PLAYER_A, "T1", "B1", "s1");
        ComplaintCommandResult supervised = service.submit(PLAYER_A, "T2", "B2", "s2");

        assertThat(service.respond(DIRECTOR, directed.complaintId(), 1, "D 回应", "r-d").ok())
                .isTrue();
        assertThat(service.respond(SUPERVISOR, supervised.complaintId(), 1,
                "S 回应", "r-s").ok()).isTrue();

        ComplaintSnapshot before = service.snapshot();
        String id = directed.complaintId();
        assertThat(service.respond(GM, id, 99, "冲突回应", "r-bad-rev").code())
                .isEqualTo(ComplaintService.REVISION_CONFLICT);
        assertThat(service.respond(GM, id, 2, "已回应后再次回应", "r-bad-status").code())
                .isEqualTo(ComplaintService.INVALID_STATUS);
        assertThat(service.respond(GM, id, 2, "   ", "r-blank").code())
                .isEqualTo(ComplaintService.INVALID_ARGUMENT);
        assertThat(service.snapshot()).isSameAs(before);
        assertThat(service.auditLog()).hasSize(4);
    }

    @Test
    void duplicateRespondKeyReturnsFirstResultWithoutChangingStateAgain() {
        ComplaintService service = service();
        ComplaintCommandResult submitted = service.submit(PLAYER_A, "标题", "正文", "s1");
        ComplaintCommandResult first = service.respond(GM, submitted.complaintId(),
                1, "首次回应", "same-response-key");
        ComplaintSnapshot afterFirst = service.snapshot();

        ComplaintCommandResult second = service.respond(GM, submitted.complaintId(),
                1, "不同回应", "same-response-key");

        assertThat(second).isEqualTo(first);
        assertThat(service.snapshot()).isSameAs(afterFirst);
        assertThat(service.getForGm(submitted.complaintId()).orElseThrow().revision())
                .isEqualTo(2);
        assertThat(service.auditLog()).hasSize(2);
    }

    @Test
    void markDeliveredAllowsTerminalStatesAndNeverResetsUnknownToPending() {
        ComplaintService service = service();
        String id = service.submit(PLAYER_A, "标题", "正文", "s1").complaintId();
        assertThat(service.markDelivered(id, ComplaintDeliveryState.SENT).code())
                .isEqualTo(ComplaintService.INVALID_STATUS);
        assertThat(service.respond(GM, id, 1, "回应", "r1").ok()).isTrue();

        ComplaintCommandResult sent = service.markDelivered(id, ComplaintDeliveryState.SENT);
        assertThat(sent.ok()).isTrue();
        assertThat(service.getForGm(id).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.SENT);
        int auditAfterSent = service.auditLog().size();

        ComplaintCommandResult same = service.markDelivered(id, ComplaintDeliveryState.SENT);
        assertThat(same.ok()).isTrue();
        assertThat(service.auditLog()).hasSize(auditAfterSent);

        assertThat(service.markDelivered(id, ComplaintDeliveryState.UNKNOWN).ok()).isTrue();
        assertThat(service.getForGm(id).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.UNKNOWN);

        ComplaintSnapshot beforeReset = service.snapshot();
        ComplaintCommandResult reset = service.markDelivered(id,
                ComplaintDeliveryState.PENDING);
        assertThat(reset.code()).isEqualTo(ComplaintService.DELIVERY_STATE_CONFLICT);
        assertThat(service.snapshot()).isSameAs(beforeReset);
        assertThat(service.getForGm(id).orElseThrow().deliveryState())
                .isEqualTo(ComplaintDeliveryState.UNKNOWN);
        assertThat(service.markDelivered(id, ComplaintDeliveryState.FAILED).ok()).isTrue();
    }

    @Test
    void storeSaveFailureKeepsMemoryAndStoredSnapshotUnchanged() {
        FailingStore store = new FailingStore();
        ComplaintService service = new ComplaintService(store, CLOCK);
        ComplaintCommandResult submitted = service.submit(PLAYER_A, "标题", "正文", "s1");
        ComplaintSnapshot before = service.snapshot();
        store.fail = true;

        ComplaintCommandResult failed = service.respond(GM, submitted.complaintId(),
                1, "回应", "r1");

        assertThat(failed.code()).isEqualTo(ComplaintService.STORE_UNAVAILABLE);
        assertThat(service.snapshot()).isSameAs(before);
        assertThat(store.load()).isSameAs(before);
        assertThat(service.getForGm(submitted.complaintId()).orElseThrow().status())
                .isEqualTo(ComplaintStatus.SUBMITTED);
        assertThat(service.auditLog()).hasSize(1);
    }

    @Test
    void snapshotCanBeReloadedAndIdempotencyReceiptsSurviveRestart() {
        InMemoryComplaintStore store = new InMemoryComplaintStore();
        ComplaintService firstProcess = new ComplaintService(store, CLOCK);
        ComplaintCommandResult submitted = firstProcess.submit(PLAYER_A, "标题", "正文", "s1");
        ComplaintCommandResult responded = firstProcess.respond(GM, submitted.complaintId(),
                1, "回应", "r1");

        ComplaintService restarted = new ComplaintService(store, CLOCK);
        assertThat(restarted.getForGm(submitted.complaintId())).isPresent();
        assertThat(restarted.snapshot()).isEqualTo(firstProcess.snapshot());
        assertThat(restarted.submit(PLAYER_A, "新标题", "新正文", "s1")).isEqualTo(submitted);
        assertThat(restarted.respond(GM, submitted.complaintId(), 1,
                "新回应", "r1")).isEqualTo(responded);
        assertThat(restarted.snapshot()).isEqualTo(firstProcess.snapshot());
    }

    @Test
    void validationRejectsBadInputsWithoutEchoingOrWritingBodies() {
        ComplaintService service = service();
        String secret = "secret-token-value";
        assertThat(service.submit(null, "t", "b", "k").code())
                .isEqualTo(ComplaintService.FORBIDDEN);
        assertThat(service.submit(PLAYER_A, " ", secret, "k").code())
                .isEqualTo(ComplaintService.INVALID_ARGUMENT);
        assertThat(service.submit(PLAYER_A, "x".repeat(201), secret, "k").code())
                .isEqualTo(ComplaintService.INVALID_ARGUMENT);
        assertThat(service.submit(PLAYER_A, "t", " ", "k").code())
                .isEqualTo(ComplaintService.INVALID_ARGUMENT);
        assertThat(service.submit(PLAYER_A, "t", "界".repeat(6000), "k").code())
                .isEqualTo(ComplaintService.INVALID_ARGUMENT);
        assertThat(service.submit(PLAYER_A, "t", "b", " ").code())
                .isEqualTo(ComplaintService.INVALID_ARGUMENT);
        assertThat(service.snapshot()).isEqualTo(ComplaintSnapshot.empty());
    }

    @Test
    void toStringAndAuditNeverExposeBodiesOrResponseText() {
        ComplaintService service = service();
        ComplaintCommandResult submitted = service.submit(PLAYER_A, "标题",
                "投诉正文绝密-A", "s1");
        service.respond(GM, submitted.complaintId(), 1, "GM回应绝密-B", "r1");
        Complaint complaint = service.getForGm(submitted.complaintId()).orElseThrow();

        assertThat(complaint.toString())
                .doesNotContain("投诉正文绝密-A")
                .doesNotContain("GM回应绝密-B");
        assertThat(service.auditLog().toString())
                .doesNotContain("投诉正文绝密-A")
                .doesNotContain("GM回应绝密-B")
                .doesNotContain("标题");
        assertThat(service.auditLog()).allSatisfy(entry ->
                assertThat(entry.contentHash()).hasSize(64));
    }

    @Test
    void returnedListsAreReadOnlyAndSnapshotCollectionMapCannotBeMutatedExternally() {
        ComplaintService service = service();
        service.submit(PLAYER_A, "标题", "正文", "s1");
        ComplaintSnapshot snapshot = service.snapshot();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.complaints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.auditLog().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ComplaintService service() {
        return new ComplaintService(new InMemoryComplaintStore(), CLOCK);
    }

    private static final class FailingStore implements ComplaintStore {
        private ComplaintSnapshot snapshot = ComplaintSnapshot.empty();
        private boolean fail;
        private final AtomicInteger saves = new AtomicInteger();

        @Override
        public ComplaintSnapshot load() {
            return snapshot;
        }

        @Override
        public void save(ComplaintSnapshot next) {
            if (fail) {
                throw new IllegalStateException("simulated disk failure");
            }
            saves.incrementAndGet();
            snapshot = next;
        }
    }
}
