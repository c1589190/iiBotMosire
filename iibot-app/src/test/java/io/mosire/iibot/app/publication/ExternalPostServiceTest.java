package io.mosire.iibot.app.publication;

import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 外部自由发帖：无批次/LLM、草稿/直接发布、幂等、目标和正文守卫。 */
class ExternalPostServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void publicDraftCreatesPendingPostWithoutBatchOrLlm() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("draft-public"))) {
            ExternalPostService service = service(fixture);

            ExternalPostService.Result result = service.compose(
                    P6Fixture.SUPERVISOR, PostVisibility.PUBLIC, null,
                    "公告标题", "这是公告正文。", "external-public-draft", false);

            assertThat(result.publishNow()).isFalse();
            Post post = result.post();
            assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
            assertThat(post.visibility()).isEqualTo(PostVisibility.PUBLIC);
            assertThat(post.authorPlayerId()).isEqualTo(PostService.SYSTEM_PUBLICATION_AUTHOR);
            assertThat(post.body()).isEqualTo("这是公告正文。");
            assertThat(post.deliveryState()).isNull();
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            assertThat(fixture.packageService.batches()).isEmpty();
        }
    }

    @Test
    void publicPublishNowMarksPublishedAndPendingDelivery() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publish-public"))) {
            ExternalPostService service = service(fixture);

            ExternalPostService.Result result = service.compose(
                    P6Fixture.SUPERVISOR, PostVisibility.PUBLIC, null,
                    "公告标题", "这是立即发布的公告。", "external-public-now", true);

            Post post = result.post();
            assertThat(result.publishNow()).isTrue();
            assertThat(post.status()).isEqualTo(PostStatus.PUBLISHED);
            assertThat(post.deliveryState()).isEqualTo(DeliveryState.PENDING);
            assertThat(post.approvedBy()).isEqualTo(P6Fixture.SUPERVISOR.actorId());
            assertThat(post.publishedBy()).isEqualTo(P6Fixture.SUPERVISOR.actorId());
            assertThat(fixture.postService.postsForGm()).hasSize(1);
        }
    }

    @Test
    void privatePublishTargetsRegisteredPlayer() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publish-private"))) {
            ExternalPostService service = service(fixture);

            ExternalPostService.Result result = service.compose(
                    P6Fixture.SUPERVISOR, PostVisibility.PRIVATE, "player-1",
                    "私信标题", "这是给玩家的私密结果。", "external-private-now", true);

            assertThat(result.post().visibility()).isEqualTo(PostVisibility.PRIVATE);
            assertThat(result.post().authorPlayerId()).isEqualTo("player-1");
            assertThat(result.post().status()).isEqualTo(PostStatus.PUBLISHED);
        }
    }

    @Test
    void unknownPrivatePlayerFailsWithoutCreatingPost() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("private-unknown"))) {
            ExternalPostService service = service(fixture);

            assertThatThrownBy(() -> service.compose(
                    P6Fixture.SUPERVISOR, PostVisibility.PRIVATE, "player-unknown",
                    "标题", "正文", "external-private-unknown", true))
                    .isInstanceOfSatisfying(ExternalPostService.ExternalPostException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    ExternalPostService.TARGET_NOT_FOUND));
            assertThat(fixture.postService.postsForGm()).isEmpty();
        }
    }

    @Test
    void sameKeyDoesNotCreateOrPublishTwice() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("idempotent"))) {
            ExternalPostService service = service(fixture);

            ExternalPostService.Result first = service.compose(
                    P6Fixture.SUPERVISOR, PostVisibility.PUBLIC, null,
                    "标题", "正文", "external-idem", true);
            ExternalPostService.Result second = service.compose(
                    P6Fixture.SUPERVISOR, PostVisibility.PUBLIC, null,
                    "标题", "正文", "external-idem", true);

            assertThat(second.post().postId()).isEqualTo(first.post().postId());
            assertThat(second.post().status()).isEqualTo(PostStatus.PUBLISHED);
            assertThat(fixture.postService.postsForGm()).hasSize(1);
        }
    }

    private static ExternalPostService service(P6Fixture fixture) {
        return new ExternalPostService(fixture.postService,
                playerId -> fixture.seededPlayerIds().contains(playerId));
    }
}
