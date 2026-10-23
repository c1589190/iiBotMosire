package io.mosire.iibot.taskflow.post;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 推文状态机、版本、幂等、可见性与持久化语义。 */
class PostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Actor playerA = new Actor("player-a", ActorRole.PLAYER);
    private final Actor playerB = new Actor("player-b", ActorRole.PLAYER);
    private final Actor gm = new Actor("gm-1", ActorRole.GM);
    private final Actor director = new Actor("director-1", ActorRole.DIRECTOR);
    private final Actor supervisor = new Actor("supervisor-1", ActorRole.SUPERVISOR);

    @Test
    void createsApprovesPublishesAndTracksDelivery() {
        InMemoryPostStore store = new InMemoryPostStore();
        PostService service = new PostService(store, CLOCK);

        PostCommandResult created = service.createDraft(playerA, "港口通报",
                "这是一篇公共推文正文", PostVisibility.PUBLIC, 0, "create-1");
        assertThat(created.ok()).isTrue();
        assertThat(created.postId()).isNotBlank();
        assertThat(created.revision()).isEqualTo(1);
        assertThat(service.collectionRevision(playerA.actorId())).isEqualTo(1);

        Post pending = service.post(created.postId()).orElseThrow();
        assertThat(pending.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
        assertThat(pending.authorPlayerId()).isEqualTo(playerA.actorId());
        assertThat(pending.approvedBy()).isNull();
        assertThat(pending.publishedBy()).isNull();
        assertThat(pending.deliveryState()).isNull();

        PostCommandResult approved = service.approve(gm, created.postId(), 1, "approve-1");
        assertThat(approved.ok()).isTrue();
        assertThat(approved.revision()).isEqualTo(2);
        Post afterApprove = service.post(created.postId()).orElseThrow();
        assertThat(afterApprove.status()).isEqualTo(PostStatus.APPROVED);
        assertThat(afterApprove.approvedBy()).isEqualTo(gm.actorId());

        PostCommandResult published = service.publish(gm, created.postId(), 2, "publish-1");
        assertThat(published.ok()).isTrue();
        assertThat(published.revision()).isEqualTo(3);
        Post afterPublish = service.post(created.postId()).orElseThrow();
        assertThat(afterPublish.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(afterPublish.publishedBy()).isEqualTo(gm.actorId());
        assertThat(afterPublish.deliveryState()).isEqualTo(DeliveryState.PENDING);

        PostCommandResult sent = service.markDelivered(created.postId(), DeliveryState.SENT);
        assertThat(sent.ok()).isTrue();
        assertThat(sent.revision()).isEqualTo(4);
        assertThat(service.post(created.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.SENT);

        assertThat(service.auditLog()).extracting(PostAuditEntry::action)
                .containsExactly(PostAuditAction.CREATE, PostAuditAction.APPROVE,
                        PostAuditAction.PUBLISH, PostAuditAction.DELIVERY);
        assertThat(service.auditLog().toString())
                .doesNotContain("这是一篇公共推文正文")
                .doesNotContain("港口通报");
        assertThat(afterPublish.toString()).doesNotContain("这是一篇公共推文正文");
    }

    @Test
    void directorAndSupervisorCanReviewButPlayerCannot() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        PostCommandResult first = service.createDraft(playerA, "t1", "body-1",
                PostVisibility.PUBLIC, 0, "c-1");
        PostCommandResult approved = service.approve(director, first.postId(), 1, "a-1");
        assertThat(approved.ok()).isTrue();
        assertThat(service.post(first.postId()).orElseThrow().approvedBy())
                .isEqualTo(director.actorId());

        PostCommandResult second = service.createDraft(playerA, "t2", "body-2",
                PostVisibility.PRIVATE, 2, "c-2");
        PostCommandResult approved2 = service.approve(supervisor, second.postId(), 1, "a-2");
        assertThat(approved2.ok()).isTrue();
        PostCommandResult published = service.publish(supervisor, second.postId(), 2, "p-2");
        assertThat(published.ok()).isTrue();

        PostCommandResult third = service.createDraft(playerA, "t3", "body-3",
                PostVisibility.PUBLIC, 4, "c-3");
        assertThat(service.publish(playerA, third.postId(), 1, "p-by-player").code())
                .isEqualTo(PostService.FORBIDDEN);
        assertThat(service.approve(playerA, third.postId(), 1, "a-by-player").code())
                .isEqualTo(PostService.FORBIDDEN);
    }

    @Test
    void rejectStoresReasonOnlyInAuditSummary() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        PostCommandResult created = service.createDraft(playerA, "待审", "绝不能进入审计的正文",
                PostVisibility.PRIVATE, 0, "c-1");

        PostCommandResult rejected = service.reject(gm, created.postId(), 1, "r-1",
                "信息不足，补充来源");

        assertThat(rejected.ok()).isTrue();
        Post post = service.post(created.postId()).orElseThrow();
        assertThat(post.status()).isEqualTo(PostStatus.REJECTED);
        assertThat(post.revision()).isEqualTo(2);
        PostAuditEntry audit = service.auditLog().get(1);
        assertThat(audit.action()).isEqualTo(PostAuditAction.REJECT);
        assertThat(audit.summary()).isEqualTo("信息不足，补充来源");
        assertThat(audit.toString()).doesNotContain("绝不能进入审计的正文")
                .doesNotContain("待审");
    }

    @Test
    void wrongRevisionStatusAndRoleHaveZeroSideEffects() {
        InMemoryPostStore store = new InMemoryPostStore();
        PostService service = new PostService(store, CLOCK);

        PostSnapshot empty = store.load();
        assertThat(service.createDraft(gm, "t", "b", PostVisibility.PUBLIC, 0, "gm-create").code())
                .isEqualTo(PostService.FORBIDDEN);
        assertThat(store.load()).isEqualTo(empty);

        PostCommandResult created = service.createDraft(playerA, "t", "b",
                PostVisibility.PUBLIC, 0, "create-ok");
        PostSnapshot afterCreate = store.load();

        assertThat(service.approve(gm, created.postId(), 99, "wrong-revision").code())
                .isEqualTo(PostService.REVISION_CONFLICT);
        assertThat(store.load()).isEqualTo(afterCreate);

        assertThat(service.approve(gm, created.postId(), 1, "approve-ok").ok()).isTrue();
        PostSnapshot afterApprove = store.load();

        assertThat(service.approve(gm, created.postId(), 2, "approve-again").code())
                .isEqualTo(PostService.INVALID_STATUS);
        assertThat(store.load()).isEqualTo(afterApprove);

        assertThat(service.publish(playerA, created.postId(), 2, "player-publish").code())
                .isEqualTo(PostService.FORBIDDEN);
        assertThat(store.load()).isEqualTo(afterApprove);
    }

    @Test
    void duplicateIdempotencyKeysReturnFirstResultWithoutDuplicatingWrites() {
        InMemoryPostStore store = new InMemoryPostStore();
        PostService service = new PostService(store, CLOCK);

        PostCommandResult first = service.createDraft(playerA, "t1", "body-one",
                PostVisibility.PUBLIC, 0, "same-create");
        PostCommandResult duplicateCreate = service.createDraft(playerA, "t2", "body-two",
                PostVisibility.PRIVATE, 99, "same-create");

        assertThat(duplicateCreate).isEqualTo(first);
        assertThat(service.postsForGm()).hasSize(1);
        assertThat(service.postsForGm().get(0).body()).isEqualTo("body-one");

        PostCommandResult approved = service.approve(gm, first.postId(), 1, "same-approve");
        PostCommandResult duplicateApprove = service.approve(gm, first.postId(), 1, "same-approve");
        assertThat(duplicateApprove).isEqualTo(approved);
        assertThat(service.post(first.postId()).orElseThrow().revision()).isEqualTo(2);

        PostCommandResult published = service.publish(gm, first.postId(), 2, "same-publish");
        PostCommandResult duplicatePublish = service.publish(gm, first.postId(), 2, "same-publish");
        assertThat(duplicatePublish).isEqualTo(published);
        assertThat(service.post(first.postId()).orElseThrow().revision()).isEqualTo(3);
        assertThat(store.load().idempotentResults()).hasSize(3);

        PostCommandResult second = service.createDraft(playerA, "t3", "body-three",
                PostVisibility.PUBLIC, 3, "create-second");
        PostCommandResult rejected = service.reject(gm, second.postId(), 1, "same-reject",
                "缺少来源");
        PostCommandResult duplicateReject = service.reject(gm, second.postId(), 1, "same-reject",
                "缺少来源");
        assertThat(duplicateReject).isEqualTo(rejected);
        assertThat(service.post(second.postId()).orElseThrow().status())
                .isEqualTo(PostStatus.REJECTED);
        assertThat(service.post(second.postId()).orElseThrow().revision()).isEqualTo(2);
        assertThat(service.auditLog()).filteredOn(entry ->
                entry.action() == PostAuditAction.REJECT).hasSize(1);
    }

    @Test
    void visibilityIsIsolatedBetweenPlayersAndSecretaries() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);

        PostCommandResult aPublicPending = service.createDraft(playerA, "A public pending",
                "A public pending body", PostVisibility.PUBLIC, 0, "a-public-pending");
        PostCommandResult aPrivate = service.createDraft(playerA, "A private",
                "A private body", PostVisibility.PRIVATE, 1, "a-private");
        PostCommandResult bPrivate = service.createDraft(playerB, "B private",
                "B private body", PostVisibility.PRIVATE, 0, "b-private");
        PostCommandResult bPublic = service.createDraft(playerB, "B public",
                "B public body", PostVisibility.PUBLIC, 1, "b-public");

        assertThat(service.approve(gm, aPrivate.postId(), 1, "aa").ok()).isTrue();
        assertThat(service.publish(gm, aPrivate.postId(), 2, "ap").ok()).isTrue();
        assertThat(service.approve(gm, bPrivate.postId(), 1, "ba").ok()).isTrue();
        assertThat(service.publish(gm, bPrivate.postId(), 2, "bp").ok()).isTrue();
        assertThat(service.approve(gm, bPublic.postId(), 1, "ba2").ok()).isTrue();
        assertThat(service.publish(gm, bPublic.postId(), 2, "bp2").ok()).isTrue();

        List<String> aPlayerVisible = service.postsVisibleToPlayer(playerA.actorId()).stream()
                .map(Post::postId).toList();
        assertThat(aPlayerVisible).containsExactlyInAnyOrder(aPrivate.postId(), bPublic.postId());
        assertThat(aPlayerVisible).doesNotContain(bPrivate.postId(), aPublicPending.postId());

        List<Post> aSecretaryVisible = service.postsVisibleToSecretary(playerA.actorId());
        assertThat(aSecretaryVisible).extracting(Post::postId)
                .containsExactlyInAnyOrder(aPublicPending.postId(), aPrivate.postId(), bPublic.postId());
        assertThat(aSecretaryVisible).extracting(Post::postId)
                .doesNotContain(bPrivate.postId());
        assertThat(aSecretaryVisible).allSatisfy(post ->
                assertThat(post.authorPlayerId().equals(playerA.actorId())
                        || (post.visibility() == PostVisibility.PUBLIC
                        && post.status() == PostStatus.PUBLISHED)).isTrue());

        List<Post> bSecretaryVisible = service.postsVisibleToSecretary(playerB.actorId());
        assertThat(bSecretaryVisible).extracting(Post::postId)
                .containsExactlyInAnyOrder(bPrivate.postId(), bPublic.postId());
        assertThat(bSecretaryVisible).extracting(Post::postId)
                .doesNotContain(aPublicPending.postId(), aPrivate.postId());

        assertThat(service.postsForGm()).hasSize(4);
        assertThat(service.pendingForGm()).extracting(Post::postId)
                .containsExactly(aPublicPending.postId());
    }

    @Test
    void storeReloadRestoresPostsRevisionAndIdempotency() {
        InMemoryPostStore store = new InMemoryPostStore();
        PostService first = new PostService(store, CLOCK);
        PostCommandResult created = first.createDraft(playerA, "t", "body",
                PostVisibility.PRIVATE, 0, "persisted-create");
        assertThat(first.approve(gm, created.postId(), 1, "persisted-approve").ok()).isTrue();

        PostService restored = new PostService(store, CLOCK);

        assertThat(restored.postsForGm()).hasSize(1);
        Post post = restored.post(created.postId()).orElseThrow();
        assertThat(post.status()).isEqualTo(PostStatus.APPROVED);
        assertThat(post.revision()).isEqualTo(2);
        assertThat(restored.collectionRevision(playerA.actorId())).isEqualTo(2);
        assertThat(restored.auditLog()).hasSize(2);
        assertThat(restored.createDraft(playerA, "other", "other body",
                PostVisibility.PUBLIC, 0, "persisted-create")).isEqualTo(created);
        assertThat(restored.postsForGm()).hasSize(1);
    }

    @Test
    void saveFailureLeavesMemoryAndStoreUnchanged() {
        FailingPostStore store = new FailingPostStore();
        store.fail = true;
        PostService service = new PostService(store, CLOCK);

        PostCommandResult createFailure = service.createDraft(playerA, "t", "body",
                PostVisibility.PUBLIC, 0, "create-fails");
        assertThat(createFailure.ok()).isFalse();
        assertThat(createFailure.code()).isEqualTo(PostService.STORE_UNAVAILABLE);
        assertThat(service.postsForGm()).isEmpty();
        assertThat(store.load()).isEqualTo(PostSnapshot.empty());

        store.fail = false;
        PostCommandResult created = service.createDraft(playerA, "t", "body",
                PostVisibility.PUBLIC, 0, "create-ok");
        assertThat(created.ok()).isTrue();
        PostSnapshot saved = store.load();
        store.fail = true;

        PostCommandResult approveFailure = service.approve(gm, created.postId(), 1, "approve-fails");
        assertThat(approveFailure.ok()).isFalse();
        assertThat(approveFailure.code()).isEqualTo(PostService.STORE_UNAVAILABLE);
        assertThat(service.post(created.postId()).orElseThrow().status())
                .isEqualTo(PostStatus.PENDING_APPROVAL);
        assertThat(store.load()).isEqualTo(saved);
    }

    @Test
    void deliveryStateRequiresPublishedAndUnknownCannotResetToPending() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        PostCommandResult created = service.createDraft(playerA, "t", "body",
                PostVisibility.PUBLIC, 0, "create");
        assertThat(service.markDelivered(created.postId(), DeliveryState.PENDING).code())
                .isEqualTo(PostService.POST_NOT_PUBLISHED);

        assertThat(service.approve(gm, created.postId(), 1, "approve").ok()).isTrue();
        assertThat(service.publish(gm, created.postId(), 2, "publish").ok()).isTrue();
        assertThat(service.markDelivered(created.postId(), DeliveryState.UNKNOWN).ok()).isTrue();
        Post unknown = service.post(created.postId()).orElseThrow();
        assertThat(unknown.deliveryState()).isEqualTo(DeliveryState.UNKNOWN);
        long unknownRevision = unknown.revision();

        PostCommandResult reset = service.markDelivered(created.postId(), DeliveryState.PENDING);
        assertThat(reset.ok()).isFalse();
        assertThat(reset.code()).isEqualTo(PostService.DELIVERY_STATE_CONFLICT);
        assertThat(service.post(created.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.UNKNOWN);
        assertThat(service.post(created.postId()).orElseThrow().revision())
                .isEqualTo(unknownRevision);

        PostCommandResult duplicate = service.markDelivered(created.postId(), DeliveryState.UNKNOWN);
        assertThat(duplicate.ok()).isTrue();
        assertThat(service.post(created.postId()).orElseThrow().revision())
                .isEqualTo(unknownRevision);
    }

    @Test
    void invalidAndOverlongInputsFailClosed() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);

        assertThat(service.createDraft(playerA, " ", "body", PostVisibility.PUBLIC, 0, "k1").code())
                .isEqualTo(PostService.INVALID_ARGUMENT);
        assertThat(service.createDraft(playerA, "t", " ", PostVisibility.PUBLIC, 0, "k2").code())
                .isEqualTo(PostService.INVALID_ARGUMENT);
        assertThat(service.createDraft(playerA, "t", "body", null, 0, "k3").code())
                .isEqualTo(PostService.INVALID_ARGUMENT);
        assertThat(service.createDraft(playerA, "t".repeat(PostService.MAX_TITLE_LENGTH + 1),
                "body", PostVisibility.PUBLIC, 0, "k4").code())
                .isEqualTo(PostService.INVALID_ARGUMENT);
        assertThat(service.createDraft(playerA, "t", "b".repeat(PostService.MAX_BODY_LENGTH + 1),
                PostVisibility.PUBLIC, 0, "k5").code())
                .isEqualTo(PostService.INVALID_ARGUMENT);
        assertThat(service.createDraft(playerA, "t", "body", PostVisibility.PUBLIC, 0, " ")
                .code()).isEqualTo(PostService.INVALID_ARGUMENT);
        assertThat(service.postsForGm()).isEmpty();
    }

    @Test
    void concurrentCreateWithSameExpectedRevisionAllowsExactlyOneWrite() throws Exception {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PostCommandResult> one = executor.submit(() -> {
                start.await();
                return service.createDraft(playerA, "t1", "body-1",
                        PostVisibility.PUBLIC, 0, "concurrent-1");
            });
            Future<PostCommandResult> two = executor.submit(() -> {
                start.await();
                return service.createDraft(playerA, "t2", "body-2",
                        PostVisibility.PUBLIC, 0, "concurrent-2");
            });
            start.countDown();
            List<PostCommandResult> results = List.of(one.get(5, TimeUnit.SECONDS),
                    two.get(5, TimeUnit.SECONDS));

            assertThat(results).filteredOn(PostCommandResult::ok).hasSize(1);
            assertThat(results).filteredOn(result ->
                    result.code().equals(PostService.REVISION_CONFLICT)).hasSize(1);
            assertThat(service.postsForGm()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 可在成功一次后切换为失败、且失败时不替换快照的测试 store。 */
    private static final class FailingPostStore implements PostStore {
        private PostSnapshot snapshot = PostSnapshot.empty();
        private boolean fail;

        @Override
        public PostSnapshot load() {
            return snapshot;
        }

        @Override
        public void save(PostSnapshot next) {
            if (fail) {
                throw new IllegalStateException("simulated store failure");
            }
            snapshot = next;
        }
    }
}
