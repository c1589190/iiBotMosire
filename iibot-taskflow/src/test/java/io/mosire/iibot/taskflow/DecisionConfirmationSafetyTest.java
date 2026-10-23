package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 硬确认的归属、hash、过期与冻结边界。 */
class DecisionConfirmationSafetyTest {

    @Test
    void anotherPlayerCannotConfirmSomeonesDraft() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口开放", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-p1");
        long revision = f.turnRevision();
        int eventsBefore = f.store.size();

        CommandResult result = f.engine.confirmDecision(f.p2, draft.draftId(),
                draft.previewHash(), revision, "confirm-by-p2");

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo(ErrorCode.DRAFT_NOT_OWNED);
        assertThat(f.turnRevision()).isEqualTo(revision);
        assertThat(f.store.size()).isEqualTo(eventsBefore);
        assertThat(f.turnView().decisions()).isEmpty();
    }

    @Test
    void wrongPreviewHashAndExpiredDraftAreRejectedWithoutStateChange() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口开放", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-p1");
        long revision = f.turnRevision();
        int eventsBefore = f.store.size();

        CommandResult wrongHash = f.engine.confirmDecision(f.p1, draft.draftId(),
                "not-the-preview-hash", revision, "wrong-hash");
        assertThat(wrongHash.code()).isEqualTo(ErrorCode.PREVIEW_HASH_MISMATCH);
        assertThat(f.turnRevision()).isEqualTo(revision);
        assertThat(f.store.size()).isEqualTo(eventsBefore);

        f.clock.advance(Duration.ofHours(2));
        CommandResult expired = f.engine.confirmDecision(f.p1, draft.draftId(),
                draft.previewHash(), revision, "expired-confirm");
        assertThat(expired.code()).isEqualTo(ErrorCode.DRAFT_EXPIRED);
        assertThat(f.turnRevision()).isEqualTo(revision);
        assertThat(f.store.size()).isEqualTo(eventsBefore);
        assertThat(f.turnView().decisions()).isEmpty();
    }

    @Test
    void confirmationAndLateDraftAreRejectedAfterLock() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口开放", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-p1");
        f.lock();
        long lockedRevision = f.turnRevision();
        int eventsBefore = f.store.size();

        CommandResult confirm = f.engine.confirmDecision(f.p1, draft.draftId(),
                draft.previewHash(), lockedRevision, "after-lock-confirm");
        assertThat(confirm.ok()).isFalse();
        assertThat(confirm.code()).isEqualTo(ErrorCode.TURN_NOT_OPEN);

        CommandResult lateDraft = f.engine.createDecisionDraft(f.p1,
                new DecisionDraftInput("late", List.of("act"), Map.of(), "target", "",
                        f.clock.instant().plus(Duration.ofHours(1))),
                lockedRevision, "late-draft");
        assertThat(lateDraft.code()).isEqualTo(ErrorCode.LATE_DRAFT);
        assertThat(f.turnRevision()).isEqualTo(lockedRevision);
        assertThat(f.store.size()).isEqualTo(eventsBefore);
        assertThat(f.turnView().state()).isEqualTo(TurnState.LOCKED);
    }

    @Test
    void onlyOneConfirmedDecisionPerPlayerAndNewerConfirmationSupersedesOtherDrafts() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口开放", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft older = f.createDraft(f.p1, "旧计划", "draft-old");
        DecisionDraft newer = f.createDraft(f.p1, "新计划", "draft-new");

        Decision decision = f.confirm(f.p1, newer, "confirm-new");
        assertThat(decision.draftId()).isEqualTo(newer.draftId());
        assertThat(f.engine.draft(older.draftId()).orElseThrow().status())
                .isEqualTo(DraftStatus.SUPERSEDED);

        DecisionDraft another = f.createDraft(f.p1, "再次尝试", "draft-another");
        CommandResult second = f.engine.confirmDecision(f.p1, another.draftId(),
                another.previewHash(), f.turnRevision(), "confirm-second");
        assertThat(second.code()).isEqualTo(ErrorCode.DECISION_ALREADY_CONFIRMED);
        assertThat(f.turnView().decisions()).hasSize(1);
    }
}
