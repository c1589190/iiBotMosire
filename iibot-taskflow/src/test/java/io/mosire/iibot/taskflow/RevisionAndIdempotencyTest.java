package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 乐观锁冲突与幂等回执不能产生额外事件或重复状态。 */
class RevisionAndIdempotencyTest {

    @Test
    void expectedRevisionConflictIsRejectedWithoutChangingState() {
        EngineFixture f = EngineFixture.create();
        int eventsBefore = f.store.size();
        long engineRevision = f.globalRevision();

        CommandResult openConflict = f.engine.openTurn(f.director, engineRevision + 10,
                "open-conflict", f.clock.instant().plus(Duration.ofHours(1)));
        assertThat(openConflict.code()).isEqualTo(ErrorCode.REVISION_CONFLICT);
        assertThat(f.globalRevision()).isEqualTo(engineRevision);
        assertThat(f.engine.turnView()).isEmpty();
        assertThat(f.store.size()).isEqualTo(eventsBefore);

        f.openTurn();
        long turnRevision = f.turnRevision();
        int eventsAfterOpen = f.store.size();
        CommandResult draftConflict = f.engine.createDecisionDraft(f.p1,
                new DecisionDraftInput("观察", List.of("look"), Map.of(), "", "",
                        f.clock.instant().plus(Duration.ofHours(1))),
                turnRevision + 10, "draft-conflict");
        assertThat(draftConflict.code()).isEqualTo(ErrorCode.REVISION_CONFLICT);
        assertThat(f.turnRevision()).isEqualTo(turnRevision);
        assertThat(f.store.size()).isEqualTo(eventsAfterOpen);
        assertThat(f.turnView().drafts()).isEmpty();
    }

    @Test
    void duplicateIdempotencyKeyReturnsFirstReceiptWithoutDuplicateEvents() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口", Visibility.PUBLIC));
        f.openTurn();
        long revisionBeforeDraft = f.turnRevision();
        int eventsBeforeDraft = f.store.size();

        DecisionDraftInput input = new DecisionDraftInput("观察", List.of("look"),
                Map.of(), "harbor", "retreat", f.clock.instant().plus(Duration.ofHours(1)));
        CommandResult firstDraft = f.expectOk(f.engine.createDecisionDraft(f.p1, input,
                revisionBeforeDraft, "same-create-key"));
        CommandResult duplicateDraft = f.engine.createDecisionDraft(f.p1,
                new DecisionDraftInput("完全不同", List.of("other"), Map.of(), "", "",
                        f.clock.instant().plus(Duration.ofHours(1))),
                revisionBeforeDraft, "same-create-key");

        assertThat(duplicateDraft).isEqualTo(firstDraft);
        assertThat(duplicateDraft.referenceId()).isEqualTo(firstDraft.referenceId());
        assertThat(duplicateDraft.eventSeq()).isEqualTo(firstDraft.eventSeq());
        assertThat(f.store.size()).isEqualTo(eventsBeforeDraft + 1);
        assertThat(f.turnView().drafts()).hasSize(1);

        DecisionDraft draft = f.engine.draft(firstDraft.referenceId().orElseThrow()).orElseThrow();
        long revisionBeforeConfirm = f.turnRevision();
        CommandResult firstConfirm = f.expectOk(f.engine.confirmDecision(f.p1, draft.draftId(),
                draft.previewHash(), revisionBeforeConfirm, "same-confirm-key"));
        int eventsAfterConfirm = f.store.size();
        CommandResult duplicateConfirm = f.engine.confirmDecision(f.p1, draft.draftId(),
                draft.previewHash(), revisionBeforeConfirm, "same-confirm-key");

        assertThat(duplicateConfirm).isEqualTo(firstConfirm);
        assertThat(duplicateConfirm.eventSeq()).isEqualTo(firstConfirm.eventSeq());
        assertThat(f.store.size()).isEqualTo(eventsAfterConfirm);
        assertThat(f.turnView().decisions()).hasSize(1);
    }

    @Test
    void duplicateOpenDoesNotCreateASecondTurnOrEvent() {
        EngineFixture f = EngineFixture.create();
        long engineRevision = f.globalRevision();
        java.time.Instant closesAt = f.clock.instant().plus(Duration.ofHours(2));
        CommandResult first = f.expectOk(f.engine.openTurn(f.director, engineRevision,
                "open-same-key", closesAt));
        int events = f.store.size();

        CommandResult duplicate = f.engine.openTurn(f.director, engineRevision,
                "open-same-key", closesAt);

        assertThat(duplicate).isEqualTo(first);
        assertThat(f.store.size()).isEqualTo(events);
        assertThat(f.engine.turnView().orElseThrow().turnNo()).isEqualTo(1);
    }

    @Test
    void onlyOneNonTerminalTurnCanExistAtATime() {
        EngineFixture f = EngineFixture.create();
        f.openTurn();
        long turnRevision = f.turnRevision();
        int events = f.store.size();
        long engineRevision = f.globalRevision();

        CommandResult second = f.engine.openTurn(f.director, engineRevision,
                "open-second-turn", f.clock.instant().plus(Duration.ofHours(2)));

        assertThat(second.code()).isEqualTo(ErrorCode.TURN_ALREADY_ACTIVE);
        assertThat(f.turnRevision()).isEqualTo(turnRevision);
        assertThat(f.globalRevision()).isEqualTo(engineRevision);
        assertThat(f.store.size()).isEqualTo(events);
        assertThat(f.engine.turnView().orElseThrow().turnNo()).isEqualTo(1);
    }

}
