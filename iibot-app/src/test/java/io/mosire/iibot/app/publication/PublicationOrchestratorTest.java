package io.mosire.iibot.app.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.PostDistributor;
import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6 发布编排：严格输出、待审核草稿、幂等、事件完整性与审核前零外发。 */
class PublicationOrchestratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void committedBatchCreatesPendingDraftsWithIsolatedAuthorsAndDoesNotSendBeforeApproval()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publication-success"))) {
            ActionBatch batch = fixture.commitBatch("北码头联合行动", "batch-1",
                    List.of(P6Fixture.action("player-1", "调查北码头"),
                            P6Fixture.action("player-2", "守住东门")));
            String publicBody = "公共叙事-北码头风暴已平息-e19a";
            String privateOne = "玩家一私密结果-发现密道-41c7";
            String privateTwo = "玩家二私密结果-东门无伤-7b62";
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(publicationJson(
                    publicBody, Map.of("player-1", privateOne, "player-2", privateTwo))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            PublicationRecord record = orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "publication-success-1");

            assertThat(record.batchId()).isEqualTo(batch.batchId());
            assertThat(record.publicationId())
                    .isEqualTo(PublicationOrchestrator.publicationIdForBatch(batch.batchId()))
                    .isEqualTo("pub-" + Hashes.sha256(batch.batchId()));
            assertThat(record.turnId()).isEqualTo(batch.turnId()).isEqualTo(fixture.turnId());
            assertThat(record.publicPostId()).isNotBlank();
            assertThat(record.privatePostIds()).containsOnlyKeys("player-1", "player-2");
            assertThat(record.publicNarrative()).isEqualTo(publicBody);
            assertThat(record.privateOutcomes()).containsEntry("player-1", privateOne)
                    .containsEntry("player-2", privateTwo);
            assertThat(fixture.postService.postsForGm()).hasSize(3).allSatisfy(post -> {
                assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
                assertThat(post.deliveryState()).isNull();
                assertThat(post.approvedBy()).isNull();
                assertThat(post.publishedBy()).isNull();
            });
            Post publicPost = fixture.postService.post(record.publicPostId()).orElseThrow();
            assertThat(publicPost.visibility()).isEqualTo(PostVisibility.PUBLIC);
            assertThat(publicPost.authorPlayerId())
                    .isEqualTo(PostService.SYSTEM_PUBLICATION_AUTHOR);
            assertThat(publicPost.body()).isEqualTo(publicBody);
            for (Map.Entry<String, String> entry : record.privatePostIds().entrySet()) {
                Post privatePost = fixture.postService.post(entry.getValue()).orElseThrow();
                assertThat(privatePost.visibility()).isEqualTo(PostVisibility.PRIVATE);
                assertThat(privatePost.authorPlayerId()).isEqualTo(entry.getKey());
                assertThat(privatePost.body()).isEqualTo(record.privateOutcomes().get(entry.getKey()));
            }

            // 完整事件 content 保存正文；summary 只放索引/hash。
            List<StoredEvent> events = fixture.publicationEvents();
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.eventType()).isEqualTo(DomainEventTypes.PUBLICATION_GENERATED);
                assertThat(event.turnId()).isEqualTo(fixture.turnId());
                JsonNode content = JSON.readTree(event.content().json());
                assertThat(content.path("publicationId").asText()).isEqualTo(record.publicationId());
                assertThat(content.path("batchId").asText()).isEqualTo(batch.batchId());
                assertThat(content.path("publicPostId").asText()).isEqualTo(record.publicPostId());
                assertThat(content.path("publicNarrative").asText()).isEqualTo(publicBody);
                assertThat(content.path("privateOutcomes").path("player-1").asText())
                        .isEqualTo(privateOne);
                assertThat(content.path("privateOutcomes").path("player-2").asText())
                        .isEqualTo(privateTwo);
                assertThat(event.payloadJson())
                        .contains(record.publicationId(), batch.batchId(), "contentHash")
                        .doesNotContain(publicBody, privateOne, privateTwo);
            });

            // 同 batch 同 key 重复生成不重复建 post/事件，也不再调用 LLM。
            PublicationRecord replay = orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "publication-success-1");
            assertThat(replay).isEqualTo(record);
            PublicationRecord differentKeyReplay = orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "publication-success-2");
            assertThat(differentKeyReplay).isEqualTo(record);
            assertThat(fixture.postService.postsForGm()).hasSize(3);
            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(llm.requests()).hasSize(1);

            // 生成完成但尚未审核/发布：PostDistributor 没有任何可发送对象。
            FakeMessagingAdapter adapter = new FakeMessagingAdapter();
            OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(1));
            PostDistributor distributor = new PostDistributor(fixture.postService, outbox,
                    "group-public", Map.of("player-1", "qq-p1", "player-2", "qq-p2"));
            assertThat(distributor.distributeIfReady()).isEmpty();
            assertThat(adapter.sent()).isEmpty();

            // 审核 + 发布后才会出站；PUBLIC→群，PRIVATE→对应玩家。
            approveAndPublish(fixture.postService, record.publicPostId(), "public");
            for (Map.Entry<String, String> entry : record.privatePostIds().entrySet()) {
                approveAndPublish(fixture.postService, entry.getValue(), "private-" + entry.getKey());
            }
            assertThat(distributor.distributeIfReady()).hasSize(3);
            assertThat(adapter.sent()).hasSize(3);
            assertThat(fixture.postService.post(record.publicPostId()).orElseThrow().deliveryState())
                    .isEqualTo(DeliveryState.SENT);
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetId()).isEqualTo("group-public");
                assertThat(message.textSegments()).containsExactly(publicBody);
            });
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetId()).isEqualTo("qq-p1");
                assertThat(message.textSegments()).containsExactly(privateOne);
            });
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetId()).isEqualTo("qq-p2");
                assertThat(message.textSegments()).containsExactly(privateTwo);
            });
        }
    }

    @Test
    void generatedPlayerTextIsSanitizedBeforePersistingPendingPosts() throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publication-sanitize"))) {
            ActionBatch batch = fixture.commitBatch("净化测试行动", "batch-sanitize",
                    List.of(P6Fixture.action("player-1", "调查北码头")));
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(publicationJson(
                    """
                            ## 公共叙事
                            postId: 11111111-1111-1111-1111-111111111111
                            码头恢复了平静。
                            """.strip(),
                    Map.of("player-1", """
                            **你的结果**
                            draftId: 22222222-2222-2222-2222-222222222222
                            你找到了密道。
                            """.strip()))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            PublicationRecord record = orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "publication-sanitize-1");

            assertThat(record.publicNarrative())
                    .contains("公共叙事", "码头恢复了平静")
                    .doesNotContain("##", "postId", "11111111");
            assertThat(record.privateOutcomes().get("player-1"))
                    .contains("你的结果", "你找到了密道")
                    .doesNotContain("**", "draftId", "22222222");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPublicationOutputs")
    void invalidModelOutputFailsStablyWithZeroPostAndZeroPublicationEvent(
            String scenario, String modelOutput) {
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("invalid-" + Integer.toUnsignedString(scenario.hashCode())))) {
            ActionBatch batch = fixture.commitOne("非法输出批次", "invalid-" + scenario,
                    "player-1", "调查码头");
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(modelOutput));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            assertThatThrownBy(() -> orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "invalid-output-key"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PublicationException.PUBLICATION_OUTPUT_INVALID));
            assertThat(fixture.postService.postsForGm()).isEmpty();
            assertThat(fixture.postService.idempotentResult(
                    "publication:" + batch.batchId() + ":public")).isEmpty();
            assertThat(fixture.publicationEvents()).isEmpty();
            assertThat(llm.requests()).hasSize(1);
        }
    }

    static Stream<Arguments> invalidPublicationOutputs() {
        return Stream.of(
                Arguments.of("非法JSON", "{not-json"),
                Arguments.of("未知字段", """
                        {"publicNarrative":"公开正文","privateOutcomes":{},"extra":"x"}
                        """),
                Arguments.of("批次外玩家", """
                        {"publicNarrative":"公开正文","privateOutcomes":{"player-9":"越权私密"}}
                        """),
                Arguments.of("空输出", """
                        {"publicNarrative":"","privateOutcomes":{}}
                        """),
                Arguments.of("公共正文超长", publicationJson(
                        "x".repeat(PostService.MAX_BODY_LENGTH + 1), Map.of())),
                Arguments.of("私密正文超长", publicationJson("公开正文",
                        Map.of("player-1", "y".repeat(PostService.MAX_BODY_LENGTH + 1)))));
    }

    @Test
    void retryWithPrivateOutcomeOmittedReusesExistingPrivateDraftWithoutOrphan() {
        AtomicBoolean failBeforePublicationEvent = new AtomicBoolean(true);
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("retry-omit-private"),
                store -> {
                    SqliteDomainEventJournal delegate = new SqliteDomainEventJournal(store);
                    return event -> {
                        if (DomainEventTypes.PUBLICATION_GENERATED.equals(event.eventType())
                                && failBeforePublicationEvent.compareAndSet(true, false)) {
                            throw new IllegalStateException("simulated failure before event append");
                        }
                        return delegate.append(event);
                    };
                })) {
            ActionBatch batch = fixture.commitOne("省略私密重试批次", "retry-omit-private",
                    "player-1", "省略私密重试行动");
            FakeLlmClient llm = FakeLlmClient.with(
                    LlmResponse.text(publicationJson("首次公共正文-A",
                            Map.of("player-1", "首次私密正文-A"))),
                    LlmResponse.text(publicationJson("第二次公共正文-B", Map.of())));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            assertThatThrownBy(() -> orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "retry-omit-private-first"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_EVENT_UNAVAILABLE));
            List<String> postIdsBefore = fixture.postService.postsForGm().stream()
                    .map(Post::postId).sorted().toList();
            assertThat(postIdsBefore).hasSize(2);
            assertThat(fixture.publicationEvents()).isEmpty();

            PublicationRecord record = orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "retry-omit-private-second");

            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm().stream()
                    .map(Post::postId).sorted().toList()).isEqualTo(postIdsBefore);
            assertThat(record.publicNarrative()).isEqualTo("首次公共正文-A");
            assertThat(record.privatePostIds()).containsOnlyKeys("player-1");
            assertThat(record.privateOutcomes())
                    .containsEntry("player-1", "首次私密正文-A");
            assertThat(fixture.postService.idempotentResult(
                    PublicationOrchestrator.privateKey(batch.batchId(), "player-1")))
                    .isPresent();
            assertThat(fixture.publicationEvents().getFirst().content().json())
                    .contains("首次公共正文-A", "首次私密正文-A")
                    .doesNotContain("第二次公共正文-B");
            assertThat(llm.requests()).hasSize(2);
        }
    }

    @Test
    void retryWithEmptySecondOutputReusesAllExistingDraftsAndCreatesOneRecord() {
        AtomicBoolean failBeforePublicationEvent = new AtomicBoolean(true);
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("retry-empty-output"),
                store -> {
                    SqliteDomainEventJournal delegate = new SqliteDomainEventJournal(store);
                    return event -> {
                        if (DomainEventTypes.PUBLICATION_GENERATED.equals(event.eventType())
                                && failBeforePublicationEvent.compareAndSet(true, false)) {
                            throw new IllegalStateException("simulated failure before event append");
                        }
                        return delegate.append(event);
                    };
                })) {
            ActionBatch batch = fixture.commitOne("空输出重试批次", "retry-empty-output",
                    "player-1", "空输出重试行动");
            FakeLlmClient llm = FakeLlmClient.with(
                    LlmResponse.text(publicationJson("空输出前公共正文-A",
                            Map.of("player-1", "空输出前私密正文-A"))),
                    LlmResponse.text(publicationJson("", Map.of())));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            assertThatThrownBy(() -> orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "retry-empty-output-first"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_EVENT_UNAVAILABLE));
            List<String> postIdsBefore = fixture.postService.postsForGm().stream()
                    .map(Post::postId).sorted().toList();
            assertThat(postIdsBefore).hasSize(2);

            PublicationRecord record = orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "retry-empty-output-second");

            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm().stream()
                    .map(Post::postId).sorted().toList()).isEqualTo(postIdsBefore);
            assertThat(record.publicPostId()).isNotBlank();
            assertThat(record.publicNarrative()).isEqualTo("空输出前公共正文-A");
            assertThat(record.privatePostIds()).containsOnlyKeys("player-1");
            assertThat(record.privateOutcomes())
                    .containsEntry("player-1", "空输出前私密正文-A");
            assertThat(fixture.publicationEvents().getFirst().content().json())
                    .contains("空输出前公共正文-A", "空输出前私密正文-A");
            assertThat(llm.requests()).hasSize(2);
        }
    }

    @Test
    void eventAppendFailureReturnsStableCodeAndRetryReusesIdempotentPostsWithoutDuplication()
            throws Exception {
        AtomicBoolean failPublicationEvent = new AtomicBoolean(true);
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("event-failure"),
                store -> {
                    SqliteDomainEventJournal delegate = new SqliteDomainEventJournal(store);
                    DomainEventJournal flaky = event -> {
                        if (failPublicationEvent.get()
                                && DomainEventTypes.PUBLICATION_GENERATED.equals(
                                event.eventType())) {
                            throw new IllegalStateException("simulated publication event failure");
                        }
                        return delegate.append(event);
                    };
                    return flaky;
                })) {
            ActionBatch batch = fixture.commitOne("事件失败批次", "event-failure",
                    "player-1", "调查旧仓库");
            FakeLlmClient llm = FakeLlmClient.with(
                    LlmResponse.text(publicationJson("公共正文-event-failure-A",
                            Map.of("player-1", "私密正文-event-failure-A"))),
                    LlmResponse.text(publicationJson("公共正文-event-failure-B",
                            Map.of("player-1", "私密正文-event-failure-B"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            assertThatThrownBy(() -> orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "event-failure-first"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_EVENT_UNAVAILABLE));
            assertThat(fixture.postService.postsForGm()).hasSize(2)
                    .allSatisfy(post -> assertThat(post.status())
                            .isEqualTo(PostStatus.PENDING_APPROVAL));
            assertThat(fixture.publicationEvents()).isEmpty();
            List<String> postIdsBeforeRetry = fixture.postService.postsForGm().stream()
                    .map(Post::postId).sorted().toList();

            failPublicationEvent.set(false);
            PublicationRecord recovered = orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "event-failure-retry");

            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm()).hasSize(2);
            assertThat(fixture.postService.postsForGm().stream()
                    .map(Post::postId).sorted().toList()).isEqualTo(postIdsBeforeRetry);
            assertThat(recovered.publicPostId()).isIn(postIdsBeforeRetry);
            assertThat(recovered.privatePostIds().values()).allMatch(postIdsBeforeRetry::contains);
            // 第二次 LLM 文本 B 不得覆盖首次已持久化 A；record 使用 A body。
            assertThat(recovered.publicNarrative()).isEqualTo("公共正文-event-failure-A");
            assertThat(recovered.privateOutcomes())
                    .containsEntry("player-1", "私密正文-event-failure-A");
            assertThat(fixture.postService.post(recovered.publicPostId()).orElseThrow().body())
                    .isEqualTo("公共正文-event-failure-A");
            assertThat(fixture.postService.post(
                    recovered.privatePostIds().get("player-1")).orElseThrow().body())
                    .isEqualTo("私密正文-event-failure-A");
            assertThat(fixture.publicationEvents().getFirst().content().json())
                    .contains("公共正文-event-failure-A",
                            "私密正文-event-failure-A")
                    .doesNotContain("公共正文-event-failure-B",
                            "私密正文-event-failure-B");
            assertThat(llm.requests()).hasSize(2);
        }
    }

    @Test
    void twoBatchesInSameTurnProduceIndependentPublicAndPrivateDraftSets() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("two-batches"))) {
            ActionBatch first = fixture.commitOne("第一包", "two-batch-1",
                    "player-1", "第一包行动");
            ActionBatch second = fixture.commitOne("第二包", "two-batch-2",
                    "player-2", "第二包行动");
            FakeLlmClient llm = FakeLlmClient.with(
                    LlmResponse.text(publicationJson("公共叙事一-91d2",
                            Map.of("player-1", "私密一-91d2"))),
                    LlmResponse.text(publicationJson("公共叙事二-77ae",
                            Map.of("player-2", "私密二-77ae"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            PublicationRecord one = orchestrator.generate(
                    P6Fixture.SUPERVISOR, first.batchId(), "two-batch-generate-1");
            PublicationRecord two = orchestrator.generate(
                    P6Fixture.SUPERVISOR, second.batchId(), "two-batch-generate-2");

            assertThat(one.turnId()).isEqualTo(two.turnId()).isEqualTo(fixture.turnId());
            assertThat(one.publicationId()).isNotEqualTo(two.publicationId());
            assertThat(one.publicPostId()).isNotEqualTo(two.publicPostId());
            assertThat(one.privatePostIds().values())
                    .doesNotContainAnyElementsOf(two.privatePostIds().values());
            assertThat(one.publicNarrative()).isEqualTo("公共叙事一-91d2");
            assertThat(one.privateOutcomes()).containsOnlyKeys("player-1")
                    .containsEntry("player-1", "私密一-91d2");
            assertThat(two.publicNarrative()).isEqualTo("公共叙事二-77ae");
            assertThat(two.privateOutcomes()).containsOnlyKeys("player-2")
                    .containsEntry("player-2", "私密二-77ae");
            assertThat(fixture.publicationEvents()).hasSize(2);
            assertThat(fixture.postService.postsForGm()).hasSize(4);
        }
    }

    @Test
    void otherCommandOccupyingPublicationKeyFailsClosedWithoutRecordOrMisattributedPost() {
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("publication-key-occupied-normal-command"))) {
            ActionBatch batch = fixture.commitOne("键占用批次一", "publication-key-normal",
                    "player-1", "键占用行动一");
            String occupiedKey = PublicationOrchestrator.publicKey(batch.batchId());
            var occupied = fixture.postService.createDraft(
                    new Actor("player-1", ActorRole.PLAYER), "占位正常草稿",
                    "占位正常草稿正文", PostVisibility.PUBLIC, 0, occupiedKey);
            assertThat(occupied.ok()).isTrue();
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(publicationJson(
                    "不应落盘的公共正文", Map.of("player-1", "不应落盘的私密正文"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            assertThatThrownBy(() -> orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "occupied-normal-generate"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_POST_UNAVAILABLE));

            assertThat(orchestrator.records()).isEmpty();
            assertThat(orchestrator.recordForBatch(batch.batchId())).isEmpty();
            assertThat(fixture.publicationEvents()).isEmpty();
            assertThat(fixture.postService.postsForGm()).singleElement().satisfies(post -> {
                assertThat(post.postId()).isEqualTo(occupied.postId());
                assertThat(post.body()).isEqualTo("占位正常草稿正文");
                assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
            });
            assertThat(fixture.postService.idempotentResult(occupiedKey))
                    .contains(occupied);
            assertThat(fixture.postService.idempotentResult(
                    PublicationOrchestrator.privateKey(batch.batchId(), "player-1"))).isEmpty();
        }

        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("publication-key-occupied-foreign-batch"))) {
            ActionBatch batch = fixture.commitOne("键占用批次二", "publication-key-foreign",
                    "player-1", "键占用行动二");
            String occupiedKey = PublicationOrchestrator.publicKey(batch.batchId());
            var occupied = fixture.postService.createPublicationDraft(P6Fixture.SUPERVISOR,
                    PostService.SYSTEM_PUBLICATION_AUTHOR, PostVisibility.PUBLIC,
                    "其他批次占位标题", "其他批次占位正文", occupiedKey);
            assertThat(occupied.ok()).isTrue();
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(publicationJson(
                    "跨批次不应落盘的公共正文", Map.of("player-1", "跨批次不应落盘的私密正文"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            assertThatThrownBy(() -> orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "occupied-foreign-generate"))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_POST_UNAVAILABLE));

            assertThat(orchestrator.records()).isEmpty();
            assertThat(orchestrator.recordForBatch(batch.batchId())).isEmpty();
            assertThat(fixture.publicationEvents()).isEmpty();
            assertThat(fixture.postService.postsForGm()).singleElement().satisfies(post -> {
                assertThat(post.postId()).isEqualTo(occupied.postId());
                assertThat(post.body()).isEqualTo("其他批次占位正文");
            });
            assertThat(fixture.postService.idempotentResult(occupiedKey))
                    .contains(occupied);
        }
    }

    @Test
    void appendCommittedThenThrownIsReturnedAsPersistedAndSurvivesReopen() throws Exception {
        Path dataDir = tempDir.resolve("append-after-commit");
        String batchId;
        PublicationRecord persisted;
        List<String> postIds;
        AtomicBoolean throwAfterCommit = new AtomicBoolean(true);
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
            ActionBatch batch = fixture.commitOne("歧义提交批次", "append-after-commit",
                    "player-1", "歧义提交行动");
            batchId = batch.batchId();
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(publicationJson(
                    "歧义提交公共正文", Map.of("player-1", "歧义提交私密正文"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);

            persisted = orchestrator.generate(P6Fixture.SUPERVISOR, batchId,
                    "append-after-commit-generate");

            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm()).hasSize(2);
            postIds = fixture.postService.postsForGm().stream().map(Post::postId).sorted().toList();
            assertThat(persisted.publicationId())
                    .isEqualTo(PublicationOrchestrator.publicationIdForBatch(batchId));
            assertThat(persisted.publicPostId()).isIn(postIds);
            assertThat(persisted.privatePostIds().values()).allMatch(postIds::contains);

            PublicationRecord replay = orchestrator.generate(P6Fixture.SUPERVISOR, batchId,
                    "append-after-commit-retry");
            assertThat(replay).isEqualTo(persisted);
            assertThat(fixture.publicationEvents()).hasSize(1);
            assertThat(fixture.postService.postsForGm()).hasSize(2);
            assertThat(llm.requests()).hasSize(1);
        }

        try (EventContentStore reopened = new EventContentStore(dataDir.resolve("events.db"))) {
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

            assertThat(reopened.readByTypePrefix(DomainEventTypes.PUBLICATION_PREFIX))
                    .hasSize(1);
            assertThat(restored.recordForBatch(batchId)).hasValueSatisfying(record ->
                    assertThat(record).isEqualTo(persisted));
            assertThat(restoredPosts.postsForGm().stream().map(Post::postId).sorted().toList())
                    .isEqualTo(postIds);
        }
    }

    @Test
    void deterministicPublicationIdAndIdenticalDuplicateEventsAreDeduplicated() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publication-dedup"))) {
            String batchId = "batch-deterministic-dedup";
            String publicationId = PublicationOrchestrator.publicationIdForBatch(batchId);
            assertThat(publicationId).isEqualTo("pub-" + Hashes.sha256(batchId));
            PublicationRecord record = manualRecord(publicationId, batchId,
                    "post-public-dedup", "相同发布记录", P6Fixture.CLOCK.instant());
            DomainEvent event = publicationEvent(record);
            fixture.journal.append(event);
            fixture.journal.append(event);

            PublicationOrchestrator orchestrator = new PublicationOrchestrator(
                    fixture.postService, fixture.packageService, FakeLlmClient.with(),
                    fixture.contentStore, fixture.journal, P6Fixture.CLOCK);

            assertThat(orchestrator.records()).containsExactly(record);
            assertThat(orchestrator.recordForBatch(batchId)).contains(record);
            assertThat(fixture.publicationEvents()).hasSize(2);
        }
    }

    @Test
    void conflictingPublicationRecordsFailClosed() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publication-conflict-same-id"))) {
            String publicationId = PublicationOrchestrator.publicationIdForBatch("batch-a");
            PublicationRecord first = manualRecord(publicationId, "batch-a",
                    "post-a", "记录一", P6Fixture.CLOCK.instant());
            PublicationRecord conflict = manualRecord(publicationId, "batch-b",
                    "post-b", "记录二", P6Fixture.CLOCK.instant());
            fixture.journal.append(publicationEvent(first));
            fixture.journal.append(publicationEvent(conflict));

            assertThatThrownBy(() -> new PublicationOrchestrator(
                    fixture.postService, fixture.packageService, FakeLlmClient.with(),
                    fixture.contentStore, fixture.journal, P6Fixture.CLOCK))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_RECORD_CORRUPT));
        }

        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("publication-conflict-same-batch"))) {
            PublicationRecord first = manualRecord(
                    PublicationOrchestrator.publicationIdForBatch("batch-same"),
                    "batch-same", "post-a", "记录一", P6Fixture.CLOCK.instant());
            PublicationRecord conflict = manualRecord(
                    PublicationOrchestrator.publicationIdForBatch("batch-other"),
                    "batch-same", "post-b", "记录二", P6Fixture.CLOCK.instant());
            fixture.journal.append(publicationEvent(first));
            fixture.journal.append(publicationEvent(conflict));

            assertThatThrownBy(() -> new PublicationOrchestrator(
                    fixture.postService, fixture.packageService, FakeLlmClient.with(),
                    fixture.contentStore, fixture.journal, P6Fixture.CLOCK))
                    .isInstanceOfSatisfying(PublicationException.class,
                            failure -> assertThat(failure.code()).isEqualTo(
                                    PublicationException.PUBLICATION_RECORD_CORRUPT));
        }
    }

    private static PublicationRecord manualRecord(String publicationId, String batchId,
                                                  String postId, String narrative,
                                                  java.time.Instant createdAt) {
        return new PublicationRecord(publicationId, batchId, null, postId,
                Map.of(), narrative, Map.of(), createdAt);
    }

    private static DomainEvent publicationEvent(PublicationRecord record) {
        return new DomainEvent(P6Fixture.CLOCK.instant(),
                DomainEventTypes.PUBLICATION_GENERATED, P6Fixture.SUPERVISOR.role(),
                P6Fixture.SUPERVISOR.actorId(), record.publicationId(), record.turnId(),
                EventVisibility.GM, record,
                Map.of("publicationId", record.publicationId(),
                        "batchId", record.batchId()));
    }

    private static void approveAndPublish(PostService service, String postId, String suffix) {
        assertThat(service.approve(P6Fixture.GM, postId, 1, "approve-" + suffix).ok()).isTrue();
        assertThat(service.publish(P6Fixture.GM, postId, 2, "publish-" + suffix).ok()).isTrue();
    }

    private static String publicationJson(String publicNarrative,
                                          Map<String, String> privateOutcomes) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("publicNarrative", publicNarrative);
            value.put("privateOutcomes", privateOutcomes);
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("测试 JSON 构造失败", failure);
        }
    }
}
