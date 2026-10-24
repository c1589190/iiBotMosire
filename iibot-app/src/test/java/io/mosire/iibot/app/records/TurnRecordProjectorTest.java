package io.mosire.iibot.app.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6 Markdown 回合投影：完整文件面、公私隔离、幂等、hash 对账与 fail-closed。 */
class TurnRecordProjectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void projectsEveryManagedFilePublicOnlyToPublicAndPrivateOnlyToOwningPlayer()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("projection-full"))) {
            ActionBatch batch = fixture.commitBatch("投影完整批次", "projection-full",
                    List.of(P6Fixture.action("player-1", "行动正文-甲-0a31"),
                            P6Fixture.action("player-2", "行动正文-乙-71bc")));
            String publicBody = "公共叙事-只进公共面-5bc1";
            String privateOne = "玩家一私密-只进玩家一-9d20";
            String privateTwo = "玩家二私密-只进玩家二-2fe4";
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(
                    publicBody, Map.of("player-1", privateOne, "player-2", privateTwo))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            PublicationRecord record = orchestrator.generate(
                    P6Fixture.SUPERVISOR, batch.batchId(), "projection-full-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);

            projector.rebuildAll();

            Path turnDir = turnDirectory(fixture);
            assertThat(turnDir.resolve("index.md")).isRegularFile();
            assertThat(turnDir.resolve("public-narrative.md")).isRegularFile();
            assertThat(turnDir.resolve("players/player-1.md")).isRegularFile();
            assertThat(turnDir.resolve("players/player-2.md")).isRegularFile();
            for (String actionId : batch.actionIds()) {
                assertThat(turnDir.resolve("actions/" + actionId + ".md")).isRegularFile();
            }
            assertThat(turnDir.resolve("packages/" + batch.packageId() + ".md"))
                    .isRegularFile();
            assertThat(turnDir.resolve("batches/" + batch.batchId() + ".md")).isRegularFile();
            assertThat(turnDir.resolve("posts/" + record.publicPostId() + "-PUBLIC.md"))
                    .isRegularFile();
            for (Map.Entry<String, String> entry : record.privatePostIds().entrySet()) {
                assertThat(turnDir.resolve("posts/" + entry.getValue() + "-PRIVATE.md"))
                        .isRegularFile();
            }

            String publicNarrative = read(turnDir.resolve("public-narrative.md"));
            assertThat(publicNarrative).contains(publicBody)
                    .doesNotContain(privateOne, privateTwo);
            String playerOne = read(turnDir.resolve("players/player-1.md"));
            assertThat(playerOne).contains(privateOne)
                    .doesNotContain(privateTwo, publicBody);
            String playerTwo = read(turnDir.resolve("players/player-2.md"));
            assertThat(playerTwo).contains(privateTwo)
                    .doesNotContain(privateOne, publicBody);

            String publicPost = read(turnDir.resolve("posts/"
                    + record.publicPostId() + "-PUBLIC.md"));
            assertThat(publicPost).contains(publicBody, "visibility: `PUBLIC`")
                    .doesNotContain(privateOne, privateTwo);
            Post privateOnePost = fixture.postService.post(
                    record.privatePostIds().get("player-1")).orElseThrow();
            String privateOneMarkdown = read(turnDir.resolve("posts/"
                    + privateOnePost.postId() + "-PRIVATE.md"));
            assertThat(privateOneMarkdown).contains(privateOne, "visibility: `PRIVATE`",
                            "authorPlayerId: `player-1`")
                    .doesNotContain(privateTwo, publicBody);
            String privateTwoMarkdown = read(turnDir.resolve("posts/"
                    + record.privatePostIds().get("player-2") + "-PRIVATE.md"));
            assertThat(privateTwoMarkdown).contains(privateTwo, "authorPlayerId: `player-2`")
                    .doesNotContain(privateOne, publicBody);

            assertThat(read(turnDir.resolve("actions/" + batch.actionIds().get(0) + ".md")))
                    .contains("行动正文-甲-0a31", "playerId: `player-1`");
            assertThat(read(turnDir.resolve("packages/" + batch.packageId() + ".md")))
                    .contains(batch.packageId(), fixture.turnId());
            assertThat(read(turnDir.resolve("batches/" + batch.batchId() + ".md")))
                    .contains(batch.batchId(), batch.packageId(), fixture.turnId());

            List<RecordSummary> summaries = projector.recordSummaries();
            assertThat(summaries).isNotEmpty();
            for (RecordSummary summary : summaries) {
                Path file = turnDir.resolve(summary.file());
                assertThat(file).isRegularFile();
                assertThat(Hashes.sha256(read(file))).isEqualTo(summary.contentHash());
                assertThat(summary.toString()).doesNotContain(
                        publicBody, privateOne, privateTwo, fixture.dataDir.toString());
            }
            assertThat(summaries).filteredOn(summary -> summary.kind().equals("public-narrative"))
                    .allSatisfy(summary -> assertThat(summary.summary())
                            .contains("publicNarrativeHash=" + record.publicNarrativeHash()));
            assertThat(summaries).filteredOn(summary -> summary.kind().equals("post"))
                    .extracting(RecordSummary::postId)
                    .contains(record.publicPostId(),
                            record.privatePostIds().get("player-1"),
                            record.privatePostIds().get("player-2"));

            String index = read(turnDir.resolve("index.md"));
            assertThat(index).contains(fixture.turnId(), "status: OPEN",
                            batch.batchId(), batch.packageId(), record.publicPostId(),
                            record.privatePostIds().get("player-1"),
                            record.privatePostIds().get("player-2"),
                            record.publicationId())
                    .doesNotContain(publicBody, privateOne, privateTwo);
            for (RecordSummary summary : summaries) {
                if (!"index.md".equals(summary.file())) {
                    assertThat(index).contains("`" + summary.file() + "`: `"
                            + summary.contentHash() + "`");
                }
            }

            StoredEvent event = fixture.publicationEvents().getFirst();
            JsonNode eventSummary = JSON.readTree(event.payloadJson());
            assertThat(eventSummary.path("contentHash").asText())
                    .isEqualTo(record.contentHash());
            assertThat(event.content().json()).contains(publicBody, privateOne, privateTwo);

            TurnRecordSummary turnSummary = projector.summaries().getFirst();
            assertThat(turnSummary.turnId()).isEqualTo(fixture.turnId());
            assertThat(turnSummary.turnNo()).isEqualTo(fixture.turnNo());
            assertThat(turnSummary.batchIds()).containsExactly(batch.batchId());
            assertThat(turnSummary.packageIds()).containsExactly(batch.packageId());
            assertThat(turnSummary.postIds()).containsExactlyInAnyOrder(
                    record.publicPostId(),
                    record.privatePostIds().get("player-1"),
                    record.privatePostIds().get("player-2"));
            assertThat(turnSummary.publicationIds()).containsExactly(record.publicationId());
        }
    }

    @Test
    void repeatedRebuildIsByteIdempotentAndKeepsSameSummaries() throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("projection-idempotent"))) {
            ActionBatch batch = fixture.commitOne("幂等投影批次", "projection-idempotent",
                    "player-1", "幂等行动");
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(
                    "幂等公共叙事", Map.of("player-1", "幂等私密结果"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            orchestrator.generate(P6Fixture.SUPERVISOR, batch.batchId(),
                    "projection-idempotent-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);

            projector.rebuildAll();
            Map<String, String> firstFiles = snapshotTree(turnDirectory(fixture));
            List<RecordSummary> firstRecords = projector.recordSummaries();
            List<TurnRecordSummary> firstTurns = projector.summaries();

            projector.rebuildAll();

            assertThat(snapshotTree(turnDirectory(fixture))).isEqualTo(firstFiles);
            assertThat(projector.recordSummaries()).isEqualTo(firstRecords);
            assertThat(projector.summaries()).isEqualTo(firstTurns);
        }
    }

    @Test
    void twoBatchesInOneTurnAreProjectedWithoutMixingPublicOrPrivateContent() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("projection-two-batches"))) {
            ActionBatch first = fixture.commitOne("投影第一包", "projection-batch-1",
                    "player-1", "第一包行动");
            ActionBatch second = fixture.commitOne("投影第二包", "projection-batch-2",
                    "player-2", "第二包行动");
            FakeLlmClient llm = FakeLlmClient.with(
                    LlmResponse.text(json("公共一-a0c1",
                            Map.of("player-1", "私密一-a0c1"))),
                    LlmResponse.text(json("公共二-d7f4",
                            Map.of("player-2", "私密二-d7f4"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            PublicationRecord one = orchestrator.generate(
                    P6Fixture.SUPERVISOR, first.batchId(), "projection-generate-1");
            PublicationRecord two = orchestrator.generate(
                    P6Fixture.SUPERVISOR, second.batchId(), "projection-generate-2");
            TurnRecordProjector projector = fixture.projector(orchestrator);

            projector.rebuildAll();

            Path turnDir = turnDirectory(fixture);
            assertThat(read(turnDir.resolve("public-narrative.md")))
                    .contains("公共一-a0c1", "公共二-d7f4")
                    .doesNotContain("私密一-a0c1", "私密二-d7f4");
            assertThat(read(turnDir.resolve("players/player-1.md")))
                    .contains("私密一-a0c1").doesNotContain("私密二-d7f4");
            assertThat(read(turnDir.resolve("players/player-2.md")))
                    .contains("私密二-d7f4").doesNotContain("私密一-a0c1");
            assertThat(read(turnDir.resolve("posts/" + one.publicPostId() + "-PUBLIC.md")))
                    .contains("公共一-a0c1").doesNotContain("公共二-d7f4");
            assertThat(read(turnDir.resolve("posts/" + two.publicPostId() + "-PUBLIC.md")))
                    .contains("公共二-d7f4").doesNotContain("公共一-a0c1");
            String index = read(turnDir.resolve("index.md"));
            assertThat(index).contains(first.batchId(), second.batchId(),
                    one.publicationId(), two.publicationId());
            assertThat(projector.summaries()).singleElement().satisfies(summary -> {
                assertThat(summary.batchIds()).containsExactlyInAnyOrder(
                        first.batchId(), second.batchId());
                assertThat(summary.postIds()).hasSize(4);
            });
        }
    }

    @Test
    void failedRebuildKeepsPreviousTargetAndLeavesNoStagingOrBackupDirectories()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("projection-atomic-failure"))) {
            ActionBatch batch = fixture.commitOne("原子投影批次", "projection-atomic-failure",
                    "player-1", "原子投影行动");
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(
                    "原子投影公共正文", Map.of("player-1", "原子投影私密正文"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            PublicationRecord record = orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "projection-atomic-failure-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();

            Path turnDir = turnDirectory(fixture);
            Map<String, String> oldFiles = snapshotTree(turnDir);
            List<RecordSummary> oldRecords = projector.recordSummaries();
            List<TurnRecordSummary> oldTurns = projector.summaries();
            assertThat(fixture.postService.approve(P6Fixture.GM, record.publicPostId(), 1,
                    "projection-atomic-approve").ok()).isTrue();

            Path turnsRoot = fixture.dataDir.resolve("records/turns");
            boolean permissionsChanged = false;
            try {
                Files.setPosixFilePermissions(turnsRoot,
                        PosixFilePermissions.fromString("r-x------"));
                permissionsChanged = true;
                assertThatThrownBy(projector::rebuildAll)
                        .isInstanceOfSatisfying(RecordProjectionException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(RecordProjectionException.IO_FAILED));
                assertThat(snapshotTree(turnDir)).isEqualTo(oldFiles);
                assertThat(projector.recordSummaries()).isEqualTo(oldRecords);
                assertThat(projector.summaries()).isEqualTo(oldTurns);
                try (Stream<Path> children = Files.list(turnsRoot)) {
                    assertThat(children.map(path -> path.getFileName().toString()))
                            .noneMatch(name -> name.startsWith(".record-staging-"))
                            .noneMatch(name -> name.startsWith(".record-backup-"));
                }
            } finally {
                if (permissionsChanged) {
                    Files.setPosixFilePermissions(turnsRoot,
                            PosixFilePermissions.fromString("rwx------"));
                }
            }

            projector.rebuildAll();
            assertThat(snapshotTree(turnDir)).isNotEqualTo(oldFiles);
            assertThat(read(turnDir.resolve("posts/" + record.publicPostId() + "-PUBLIC.md")))
                    .contains("status: `APPROVED`");
            try (Stream<Path> children = Files.list(turnsRoot)) {
                assertThat(children.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".record-staging-"))
                        .noneMatch(name -> name.startsWith(".record-backup-"));
            }
        }
    }

    @Test
    void unsafeIdsAndProjectionPathEscapeFailClosedWithoutRollingBackDomainEvents()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("projection-unsafe"))) {
            ActionBatch batch = fixture.commitOne("失败投影批次", "projection-unsafe",
                    "player-1", "失败投影行动");
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(
                    "失败投影公共正文", Map.of("player-1", "失败投影私密正文"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            PublicationRecord record = orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "projection-unsafe-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            var snapshotBefore = fixture.postService.snapshot();
            int eventCountBefore = fixture.contentStore.readAfter(0, 10_000).size();

            assertThatThrownBy(() -> TurnRecordProjector.safeLogicalId("../escape", "turnId"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));
            assertThatThrownBy(() -> TurnRecordProjector.safeLogicalId("bad player", "playerId"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));

            Path records = fixture.dataDir.resolve("records");
            Files.createDirectories(records);
            Path outside = tempDir.resolve("projection-outside");
            Files.createDirectories(outside);
            Files.createSymbolicLink(records.resolve("turns"), outside);

            assertThatThrownBy(projector::rebuildAll)
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));
            assertThat(fixture.postService.snapshot()).isEqualTo(snapshotBefore);
            assertThat(fixture.contentStore.readAfter(0, 10_000)).hasSize(eventCountBefore);
            assertThat(fixture.postService.post(record.publicPostId())).hasValueSatisfying(post ->
                    assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL));
            try (Stream<Path> files = Files.list(outside)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void unsafePlayerIdInsidePublicationFailsProjectionClosed() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("projection-unsafe-player"))) {
            ActionBatch batch = fixture.commitOne("非法玩家批次", "projection-unsafe-player",
                    "../unsafe-player", "非法玩家行动");
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json("",
                    Map.of("../unsafe-player", "非法玩家私密结果"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            orchestrator.generate(P6Fixture.SUPERVISOR, batch.batchId(),
                    "projection-unsafe-player-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);

            assertThatThrownBy(projector::rebuildAll)
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));
            assertThat(fixture.postService.postsForGm()).hasSize(1);
        }
    }

    private static Path turnDirectory(P6Fixture fixture) {
        return fixture.dataDir.resolve("records/turns")
                .resolve(fixture.turnNo() + "-" + fixture.turnId());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("读取投影文件失败 " + file.getFileName(), failure);
        }
    }

    private static Map<String, String> snapshotTree(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(file).toString().replace('\\', '/'),
                        read(file));
            }
        }
        return result;
    }

    private static String json(String publicNarrative, Map<String, String> privateOutcomes) {
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
