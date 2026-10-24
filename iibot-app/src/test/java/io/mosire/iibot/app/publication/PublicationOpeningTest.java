package io.mosire.iibot.app.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.publication.EventSourcedPostStore;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P8-A 独立公共开场推文：OPENING 记录不变量、幂等、失败语义与旧 BATCH 兼容。 */
class PublicationOpeningTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir
    Path tempDir;

    @Test
    void openingCreatesPublicPendingDraftWithNullBatchTurnAndEmptyPrivateMaps() throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("opening-success"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            String title = "雾灯港·序章-3c91";
            String body = "潮水退去后，北码头只剩一盏雾灯-7a2e";
            String key = "opening-success-1";

            PublicationRecord record = orchestrator.createOpening(
                    P6Fixture.SUPERVISOR, title, body, key);

            assertThat(record.kind()).isEqualTo(PublicationKind.OPENING);
            assertThat(record.publicationId()).isEqualTo(
                    PublicationOrchestrator.publicationIdForOpening(
                            P6Fixture.SUPERVISOR, key));
            assertThat(record.batchId()).isNull();
            assertThat(record.turnId()).isNull();
            assertThat(record.privatePostIds()).isEmpty();
            assertThat(record.privateOutcomes()).isEmpty();
            assertThat(record.publicPostId()).isNotBlank();
            assertThat(record.publicNarrative()).isEqualTo(body);
            assertThat(record.contentHash()).hasSize(64);
            assertThat(record.publicNarrativeHash()).hasSize(64);
            assertThat(record.toString()).doesNotContain(body);

            Post post = fixture.postService.post(record.publicPostId()).orElseThrow();
            assertThat(post.visibility()).isEqualTo(PostVisibility.PUBLIC);
            assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
            assertThat(post.authorPlayerId())
                    .isEqualTo(PostService.SYSTEM_PUBLICATION_AUTHOR);
            assertThat(post.body()).isEqualTo(body);
            assertThat(post.deliveryState()).isNull();
            assertThat(post.approvedBy()).isNull();
            assertThat(post.publishedBy()).isNull();

            List<StoredEvent> events = fixture.publicationEvents();
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.eventType())
                        .isEqualTo(DomainEventTypes.PUBLICATION_GENERATED);
                assertThat(event.turnId()).isNull();
                JsonNode content = JSON.readTree(event.content().json());
                assertThat(content.path("kind").asText()).isEqualTo("OPENING");
                assertThat(content.path("publicationId").asText())
                        .isEqualTo(record.publicationId());
                assertThat(content.path("batchId").isNull()).isTrue();
                assertThat(content.path("turnId").isNull()).isTrue();
                assertThat(content.path("publicPostId").asText())
                        .isEqualTo(record.publicPostId());
                assertThat(content.path("privatePostIds").isObject()).isTrue();
                assertThat(content.path("privatePostIds").size()).isZero();
                assertThat(content.path("privateOutcomes").size()).isZero();
                assertThat(content.path("publicNarrative").asText()).isEqualTo(body);
                assertThat(event.payloadJson())
                        .contains(record.publicationId(), "contentHash", "kind")
                        .doesNotContain(body, title);
            });
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            assertThat(fixture.packageService.batches()).isEmpty();
            assertThat(fixture.engine.exportState().turns()).hasSize(1);
        }
    }

    @Test
    void sameOpeningKeyWithDifferentTitleOrBodyReturnsFirstRecordWithoutOverwriting()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("opening-idempotent"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            PublicationRecord first = orchestrator.createOpening(
                    P6Fixture.SUPERVISOR, "首次标题", "首次正文-a1", "opening-idem");
            PublicationRecord second = orchestrator.createOpening(
                    P6Fixture.SUPERVISOR, "第二次标题", "第二次正文-b2", "opening-idem");

            assertThat(second).isEqualTo(first);
            Post post = fixture.postService.post(first.publicPostId()).orElseThrow();
            assertThat(post.title()).isEqualTo("首次标题");
            assertThat(post.body()).isEqualTo("首次正文-a1");
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.publicationEvents().getFirst().content().json())
                    .contains("首次正文-a1")
                    .doesNotContain("第二次正文-b2");

            assertThat(orchestrator.records()).containsExactly(first);
            assertThat(orchestrator.record(first.publicationId())).contains(first);
        }
    }

    @Test
    void openingRejectsBlankOverlongIllegalKeyAndNonGmWithZeroPost() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("opening-invalid"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            assertInvalid(orchestrator, P6Fixture.SUPERVISOR, " ", "正文",
                    "key", PublicationException.INVALID_ARGUMENTS);
            assertInvalid(orchestrator, P6Fixture.SUPERVISOR, "标题", " ",
                    "key", PublicationException.INVALID_ARGUMENTS);
            assertInvalid(orchestrator, P6Fixture.SUPERVISOR,
                    "x".repeat(PostService.MAX_TITLE_LENGTH + 1), "正文",
                    "key", PublicationException.INVALID_ARGUMENTS);
            assertInvalid(orchestrator, P6Fixture.SUPERVISOR, "标题",
                    "y".repeat(PostService.MAX_BODY_LENGTH + 1),
                    "key", PublicationException.INVALID_ARGUMENTS);
            assertInvalid(orchestrator, P6Fixture.SUPERVISOR, "标题", "正文",
                    " ", PublicationException.INVALID_ARGUMENTS);
            assertInvalid(orchestrator, P6Fixture.SUPERVISOR, "标题", "正文",
                    "k".repeat(PostService.MAX_IDEMPOTENCY_KEY_LENGTH + 1),
                    PublicationException.INVALID_ARGUMENTS);
            assertInvalid(orchestrator, new Actor("player-1", ActorRole.PLAYER),
                    "标题", "正文", "key", PublicationException.FORBIDDEN);
            assertInvalid(orchestrator, null, "标题", "正文", "key",
                    PublicationException.FORBIDDEN);

            assertThat(fixture.postService.postsForGm()).isEmpty();
            assertThat(fixture.publicationEvents()).isEmpty();
        }
    }

    @Test
    void openingEventAppendFailureReturnsStableCodeAndRetryReusesDraftWithoutDuplication() {
        AtomicBoolean failAppend = new AtomicBoolean(true);
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("opening-append-before-commit"),
                store -> {
                    SqliteDomainEventJournal delegate = new SqliteDomainEventJournal(store);
                    return event -> {
                        if (failAppend.get()
                                && DomainEventTypes.PUBLICATION_GENERATED.equals(
                                event.eventType())) {
                            throw new IllegalStateException("simulated append failure");
                        }
                        return delegate.append(event);
                    };
                })) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            assertThatThrownBy(() -> orchestrator.createOpening(P6Fixture.SUPERVISOR,
                    "失败标题", "首次正文-append-fail", "opening-append-fail"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_EVENT_UNAVAILABLE));
            assertThat(fixture.publicationEvents()).isEmpty();
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            String postId = fixture.postService.postsForGm().getFirst().postId();

            failAppend.set(false);
            PublicationRecord recovered = orchestrator.createOpening(
                    P6Fixture.SUPERVISOR, "失败标题", "第二次正文-不得覆盖",
                    "opening-append-fail");

            assertThat(recovered.publicPostId()).isEqualTo(postId);
            assertThat(recovered.publicNarrative()).isEqualTo("首次正文-append-fail");
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            assertThat(fixture.postService.post(postId).orElseThrow().body())
                    .isEqualTo("首次正文-append-fail");
            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.publicationEvents().getFirst().content().json())
                    .contains("首次正文-append-fail")
                    .doesNotContain("第二次正文-不得覆盖");
        }
    }

    @Test
    void openingAppendCommittedThenThrownIsReturnedAsPersistedAndSurvivesReopen()
            throws Exception {
        Path dataDir = tempDir.resolve("opening-append-after-commit");
        AtomicBoolean throwAfterCommit = new AtomicBoolean(true);
        PublicationRecord persisted;
        try (P6Fixture fixture = P6Fixture.open(dataDir, store -> {
            SqliteDomainEventJournal delegate = new SqliteDomainEventJournal(store);
            return event -> {
                long seq = delegate.append(event);
                if (DomainEventTypes.PUBLICATION_GENERATED.equals(event.eventType())
                        && throwAfterCommit.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated exception after commit");
                }
                return seq;
            };
        })) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            persisted = orchestrator.createOpening(P6Fixture.SUPERVISOR,
                    "歧义提交开场标题", "歧义提交开场正文-c0de", "opening-after-commit");

            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            assertThat(fixture.postService.post(persisted.publicPostId()).orElseThrow().body())
                    .isEqualTo("歧义提交开场正文-c0de");
            PublicationRecord replay = orchestrator.createOpening(P6Fixture.SUPERVISOR,
                    "不同标题", "不同正文", "opening-after-commit");
            assertThat(replay).isEqualTo(persisted);
            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm()).hasSize(1);
        }

        try (EventContentStore reopened = new EventContentStore(
                dataDir.resolve("events.db"))) {
            DomainEventJournal journal = new SqliteDomainEventJournal(reopened);
            PostService restoredPosts = new PostService(
                    new EventSourcedPostStore(reopened, journal, P6Fixture.CLOCK),
                    P6Fixture.CLOCK);
            ActionPackageService packages = new ActionPackageService(
                    new ActionPoolService(P6Fixture.CLOCK), new DecisionPackages(),
                    P6Fixture.CLOCK);
            PublicationOrchestrator restored = new PublicationOrchestrator(
                    restoredPosts, packages, FakeLlmClient.with(), reopened, journal,
                    P6Fixture.CLOCK);

            assertThat(restored.record(persisted.publicationId()))
                    .hasValueSatisfying(record -> assertThat(record).isEqualTo(persisted));
            assertThat(restoredPosts.postsForGm()).hasSize(1);
            assertThat(reopened.readByTypePrefix(DomainEventTypes.PUBLICATION_PREFIX))
                    .hasSize(1);
        }
    }

    @Test
    void openingRecordRejectsBatchTurnPrivateMapsAndMissingPublicFields() {
        Instant now = P6Fixture.CLOCK.instant();
        assertThatThrownBy(() -> new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", "batch-x", null, "post-x", Map.of(), "正文",
                Map.of(), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchId");
        assertThatThrownBy(() -> new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", null, "turn-x", "post-x", Map.of(), "正文",
                Map.of(), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turnId");
        assertThatThrownBy(() -> new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", null, null, "post-x",
                Map.of("player-1", "post-private"), "正文", Map.of(), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("privatePostIds");
        assertThatThrownBy(() -> new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", null, null, "post-x", Map.of(), "正文",
                Map.of("player-1", "私密"), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("privateOutcomes");
        assertThatThrownBy(() -> new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", null, null, null, Map.of(), "", Map.of(), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicPostId");
        assertThatThrownBy(() -> new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", null, null, "post-x", Map.of(), " ", Map.of(), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicNarrative");

        PublicationRecord valid = new PublicationRecord(PublicationKind.OPENING,
                "pub-opening", null, null, "post-x", Map.of(), "正文", Map.of(), now);
        assertThat(valid.kind()).isEqualTo(PublicationKind.OPENING);
        assertThat(valid.batchId()).isNull();
        assertThat(valid.turnId()).isNull();
    }

    @Test
    void legacyBatchJsonWithoutKindDeserializesAsBatchAndStillRequiresBatchId() throws Exception {
        String legacy = """
                {
                  "publicationId": "pub-legacy-batch",
                  "batchId": "batch-legacy",
                  "turnId": null,
                  "publicPostId": "post-public",
                  "privatePostIds": {},
                  "publicNarrative": "旧版公共正文",
                  "privateOutcomes": {},
                  "createdAt": "2026-10-24T00:00:00Z"
                }
                """;
        PublicationRecord record = JSON.readValue(legacy, PublicationRecord.class);
        assertThat(record.kind()).isEqualTo(PublicationKind.BATCH);
        assertThat(record.batchId()).isEqualTo("batch-legacy");
        assertThat(record.publicNarrative()).isEqualTo("旧版公共正文");

        String missingBatch = """
                {
                  "publicationId": "pub-legacy-missing-batch",
                  "publicPostId": "post-public",
                  "privatePostIds": {},
                  "publicNarrative": "旧版公共正文",
                  "privateOutcomes": {},
                  "createdAt": "2026-10-24T00:00:00Z"
                }
                """;
        assertThatThrownBy(() -> JSON.readValue(missingBatch, PublicationRecord.class))
                .hasMessageContaining("batchId");
    }

    private static void assertInvalid(PublicationOrchestrator orchestrator, Actor gm,
                                      String title, String body, String key,
                                      String expectedCode) {
        assertThatThrownBy(() -> orchestrator.createOpening(gm, title, body, key))
                .isInstanceOfSatisfying(PublicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expectedCode));
    }
}
