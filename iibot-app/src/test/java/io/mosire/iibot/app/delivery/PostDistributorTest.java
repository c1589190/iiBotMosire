package io.mosire.iibot.app.delivery;

import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class PostDistributorTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor SUPERVISOR = new Actor("external-supervisor", ActorRole.SUPERVISOR);

    @Test
    void publicAndPrivatePostsGoToTheExactConfiguredTargetsOnceWithStableKeys() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        Post publicPost = published(service, PostVisibility.PUBLIC, "公共推文正文");
        Post privatePost = published(service, PostVisibility.PRIVATE, "私人推文正文");
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        PostDistributor distributor = distributor(service, adapter, Map.of("player-1", "qq-p1"));

        List<SendOutcome> outcomes = distributor.distributeIfReady();

        assertThat(outcomes).hasSize(2).allMatch(SendOutcome::sent);
        assertThat(distributor.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(2);
        OutboundMessage publicMessage = adapter.sent().stream()
                .filter(message -> message.textSegments().contains("公共推文正文"))
                .findFirst().orElseThrow();
        assertThat(publicMessage.targetType()).isEqualTo(OutboundMessage.TargetType.GROUP);
        assertThat(publicMessage.targetId()).isEqualTo("group-public");
        assertThat(publicMessage.idempotencyKey())
                .isEqualTo("public:post:" + publicPost.postId() + ":public:reply:0");
        OutboundMessage privateMessage = adapter.sent().stream()
                .filter(message -> message.textSegments().contains("私人推文正文"))
                .findFirst().orElseThrow();
        assertThat(privateMessage.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
        assertThat(privateMessage.targetId()).isEqualTo("qq-p1");
        assertThat(privateMessage.idempotencyKey())
                .isEqualTo("private:post:" + privatePost.postId() + ":private:reply:0");
        assertThat(service.post(publicPost.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.SENT);
        assertThat(service.post(privatePost.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.SENT);
    }

    @Test
    void unknownSendIsPersistedAsUnknownAndNeverRetriedEvenByNewDistributor() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        Post post = published(service, PostVisibility.PUBLIC, "结果不确定的正文");
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(
                        SendReceipt.unknown("TIMEOUT", "模拟发送结果未知")));
        PostDistributor first = distributor(service, adapter, Map.of());

        List<SendOutcome> outcomes = first.distributeIfReady();

        assertThat(outcomes).singleElement().matches(SendOutcome::unknown);
        assertThat(service.post(post.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.UNKNOWN);
        assertThat(first.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(1);

        // 模拟同一进程内重新装配：持久化的 UNKNOWN 必须阻止再次发送。
        PostDistributor second = distributor(service, adapter, Map.of());
        assertThat(second.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(1);
        assertThat(service.post(post.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.UNKNOWN);
    }

    @Test
    void failedBeforeSendIsPersistedAsFailedAndNotRetried() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        Post post = published(service, PostVisibility.PUBLIC, "明确失败正文");
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(
                        SendReceipt.failed("ADAPTER_REJECTED", "适配器明确拒绝")));
        PostDistributor distributor = distributor(service, adapter, Map.of());

        assertThat(distributor.distributeIfReady()).singleElement()
                .matches(outcome -> outcome.state() == SendReceipt.State.FAILED_BEFORE_SEND);
        assertThat(service.post(post.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.FAILED);
        assertThat(distributor(service, adapter, Map.of()).distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(1);
    }

    @Test
    void privatePostWithoutPlayerQqMappingFailsSafelyWithoutSendingOrThrowing() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        Post post = published(service, PostVisibility.PRIVATE, "没有映射的正文");
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        PostDistributor distributor = distributor(service, adapter, Map.of());

        List<SendOutcome> outcomes = distributor.distributeIfReady();

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.state()).isEqualTo(SendReceipt.State.FAILED_BEFORE_SEND);
            assertThat(outcome.errorCode()).isEqualTo("UNKNOWN_PLAYER");
            assertThat(outcome.idempotencyKey())
                    .isEqualTo("private:post:" + post.postId() + ":private:reply:0");
        });
        assertThat(adapter.sent()).isEmpty();
        assertThat(service.post(post.postId()).orElseThrow().deliveryState())
                .isEqualTo(DeliveryState.FAILED);
        assertThat(distributor.distributeIfReady()).isEmpty();
    }

    @Test
    void pendingDraftIsNeverSent() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        ok(service.createDraft(PLAYER, "待审标题", "待审正文", PostVisibility.PUBLIC,
                service.collectionRevision("player-1"), "pending-create"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();

        assertThat(distributor(service, adapter, Map.of("player-1", "qq-p1"))
                .distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).isEmpty();
    }

    private static Post published(PostService service, PostVisibility visibility, String body) {
        PostCommandResult created = service.createDraft(PLAYER, "标题-" + visibility,
                body, visibility, service.collectionRevision("player-1"),
                "create-" + visibility + "-" + body.hashCode());
        ok(created);
        Post draft = service.post(created.postId()).orElseThrow();
        ok(service.approve(SUPERVISOR, draft.postId(), draft.revision(),
                "approve-" + draft.postId()));
        Post approved = service.post(created.postId()).orElseThrow();
        ok(service.publish(SUPERVISOR, approved.postId(), approved.revision(),
                "publish-" + approved.postId()));
        return service.post(created.postId()).orElseThrow();
    }

    private static PostDistributor distributor(PostService service, FakeMessagingAdapter adapter,
                                               Map<String, String> playerQqById) {
        return new PostDistributor(service,
                new OutboxSender(adapter, Duration.ofSeconds(2)),
                "group-public", playerQqById);
    }

    private static void ok(PostCommandResult result) {
        assertThat(result.ok()).as("command %s: %s", result.code(), result.message()).isTrue();
    }
}
