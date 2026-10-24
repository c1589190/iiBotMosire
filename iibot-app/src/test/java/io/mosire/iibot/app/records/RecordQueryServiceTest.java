package io.mosire.iibot.app.records;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6 records 查询面：过滤、摘要无正文、安全相对文件与物理路径隐藏。 */
class RecordQueryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void listGetAndQueryFilterCorrectlyWithoutLeakingBodiesOrPhysicalPaths() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("query"))) {
            ActionBatch batch = fixture.commitBatch("查询批次", "query",
                    List.of(P6Fixture.action("player-1", "查询行动一"),
                            P6Fixture.action("player-2", "查询行动二")));
            String publicBody = "查询公共正文-3e10";
            String privateOne = "查询玩家一私密-6a02";
            String privateTwo = "查询玩家二私密-b4c7";
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(publicBody,
                    Map.of("player-1", privateOne, "player-2", privateTwo))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            PublicationRecord record = orchestrator.generate(P6Fixture.SUPERVISOR,
                    batch.batchId(), "query-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();
            RecordQueryService query = new RecordQueryService(fixture.dataDir, projector);

            List<TurnRecordSummary> turns = query.listTurns();
            assertThat(turns).singleElement().satisfies(summary -> {
                assertThat(summary.turnId()).isEqualTo(fixture.turnId());
                assertThat(summary.batchIds()).containsExactly(batch.batchId());
                assertThat(summary.packageIds()).containsExactly(batch.packageId());
                assertThat(summary.postIds()).containsExactlyInAnyOrder(
                        record.publicPostId(),
                        record.privatePostIds().get("player-1"),
                        record.privatePostIds().get("player-2"));
                assertThat(summary.toString())
                        .doesNotContain(publicBody, privateOne, privateTwo,
                                fixture.dataDir.toString());
            });
            assertThat(query.turnList().toString())
                    .doesNotContain(publicBody, privateOne, privateTwo,
                            fixture.dataDir.toString());

            TurnRecordFile index = query.getTurn(fixture.turnId(), "index");
            assertThat(index.file()).isEqualTo("index.md");
            assertThat(index.content()).contains(batch.batchId(), record.publicationId());
            assertThat(index.contentHash()).hasSize(64);
            assertThat(index.toString()).doesNotContain(fixture.dataDir.toString());
            assertThat(query.turnGet(fixture.turnId(), "public-narrative").content())
                    .contains(publicBody).doesNotContain(privateOne, privateTwo);
            assertThat(query.getTurn(fixture.turnId(), "players/player-1.md").content())
                    .contains(privateOne).doesNotContain(privateTwo, publicBody);
            assertThat(query.getTurn(fixture.turnId(), "players/player-2").content())
                    .contains(privateTwo).doesNotContain(privateOne, publicBody);

            List<RecordSummary> postByPlayerOne = query.query(
                    "posts", fixture.turnId(), "player-1", null, null);
            assertThat(postByPlayerOne).singleElement().satisfies(summary -> {
                assertThat(summary.kind()).isEqualTo("post");
                assertThat(summary.playerId()).isEqualTo("player-1");
                assertThat(summary.postId()).isEqualTo(
                        record.privatePostIds().get("player-1"));
                assertThat(summary.file()).isEqualTo("posts/"
                        + record.privatePostIds().get("player-1") + "-PRIVATE.md");
            });
            assertThat(query.query("batch", null, null, null, batch.batchId()))
                    .singleElement().satisfies(summary -> {
                        assertThat(summary.batchId()).isEqualTo(batch.batchId());
                        assertThat(summary.summary()).contains("status=COMMITTED");
                    });
            assertThat(query.query("action", fixture.turnId(), "player-2",
                            null, null))
                    .singleElement().satisfies(summary ->
                            assertThat(summary.actionId()).isEqualTo(batch.actionIds().get(1)));
            assertThat(query.query("post", fixture.turnId(), null,
                            record.publicPostId(), null))
                    .singleElement().satisfies(summary -> {
                        assertThat(summary.kind()).isEqualTo("post");
                        assertThat(summary.postId()).isEqualTo(record.publicPostId());
                    });
            assertThat(query.query("public-narrative", null, null, null, null))
                    .singleElement().satisfies(summary ->
                            assertThat(summary.summary()).contains("publicNarrativeHash="));
            assertThat(query.query().toString())
                    .doesNotContain(publicBody, privateOne, privateTwo,
                            fixture.dataDir.toString());
            assertThatThrownBy(() -> query.query("unknown-kind", null, null, null, null))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.INVALID_ARGUMENTS));
        }
    }

    @Test
    void getTurnAcceptsOnlySafeRelativeFilesAndRejectsSymlinkTraversal() throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("query-safe"))) {
            ActionBatch batch = fixture.commitOne("安全路径批次", "query-safe",
                    "player-1", "安全路径行动");
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(
                    "安全路径公共正文", Map.of("player-1", "安全路径私密正文"))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            orchestrator.generate(P6Fixture.SUPERVISOR, batch.batchId(), "query-safe-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();
            RecordQueryService query = new RecordQueryService(fixture.dataDir, projector);

            for (String file : List.of("../escape", "players/../index.md", "/etc/passwd",
                    "secret.md", "players/", "players/player-1.md/extra")) {
                assertThatThrownBy(() -> query.getTurn(fixture.turnId(), file))
                        .as("file=%s", file)
                        .isInstanceOfSatisfying(RecordProjectionException.class,
                                failure -> assertThat(failure.code()).isIn(
                                        RecordProjectionException.UNSAFE_PATH,
                                        RecordProjectionException.INVALID_ARGUMENTS));
            }
            for (String turnId : List.of("../escape", "bad turn", "turn/child")) {
                assertThatThrownBy(() -> query.getTurn(turnId, "index.md"))
                        .as("turnId=%s", turnId)
                        .isInstanceOfSatisfying(RecordProjectionException.class,
                                failure -> assertThat(failure.code()).isEqualTo(
                                        RecordProjectionException.UNSAFE_PATH));
            }

            Path playerFile = fixture.dataDir.resolve("records/turns")
                    .resolve(fixture.turnNo() + "-" + fixture.turnId())
                    .resolve("players/player-1.md");
            Path outside = tempDir.resolve("outside-query-secret.md");
            Files.writeString(outside, "不应被读取的外部正文");
            Files.delete(playerFile);
            Files.createSymbolicLink(playerFile, outside);

            assertThatThrownBy(() -> query.getTurn(fixture.turnId(), "players/player-1.md"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));
        }
    }

    @Test
    void manifestFileSetAndHashTamperingFailClosedAndUnindexedContentIsNotExposed()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("query-manifest"))) {
            ActionBatch batch = fixture.commitBatch("清单校验批次", "query-manifest",
                    List.of(P6Fixture.action("player-1", "清单行动一"),
                            P6Fixture.action("player-2", "清单行动二")));
            String publicBody = "清单公共正文-b11e";
            String privateOne = "清单玩家一私密-43da";
            String privateTwo = "清单玩家二私密-a8c1";
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(publicBody,
                    Map.of("player-1", privateOne, "player-2", privateTwo))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            orchestrator.generate(P6Fixture.SUPERVISOR, batch.batchId(),
                    "query-manifest-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();
            RecordQueryService projected = new RecordQueryService(fixture.dataDir, projector);
            RecordQueryService fsOnly = new RecordQueryService(fixture.dataDir);
            Path turnDir = fixture.dataDir.resolve("records/turns")
                    .resolve(fixture.turnNo() + "-" + fixture.turnId());

            String unindexedSecret = "未索引秘密正文-不应暴露-f012";
            Files.writeString(turnDir.resolve("players/intruder.md"), unindexedSecret);

            assertThat(fsOnly.listTurns()).isEmpty();
            assertThat(fsOnly.query()).isEmpty();
            assertThat(fsOnly.query().toString()).doesNotContain(unindexedSecret);
            assertThat(projected.query().toString())
                    .doesNotContain(unindexedSecret, "players/intruder.md",
                            fixture.dataDir.toString());
            assertThatThrownBy(() -> projected.getTurn(fixture.turnId(), "index.md"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.RECORD_INCONSISTENT));
            assertThatThrownBy(() -> fsOnly.getTurn(fixture.turnId(), "index.md"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.RECORD_INCONSISTENT));
            Files.delete(turnDir.resolve("players/intruder.md"));

            Files.writeString(turnDir.resolve("players/player-1.md"),
                    "hash 被篡改的玩家一正文");
            assertThatThrownBy(() -> projected.getTurn(fixture.turnId(), "players/player-1.md"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.RECORD_INCONSISTENT));
            assertThatThrownBy(() -> fsOnly.getTurn(fixture.turnId(), "players/player-1.md"))
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.RECORD_INCONSISTENT));
        }
    }

    @Test
    void fsOnlyRejectsRecordsParentAndTurnsSymlinkEscapesAndTreatsEmptyRecordsAsEmpty()
            throws Exception {
        Path emptyData = tempDir.resolve("query-fs-empty-records");
        Files.createDirectories(emptyData.resolve("records"));
        RecordQueryService empty = new RecordQueryService(emptyData);
        assertThat(empty.listTurns()).isEmpty();
        assertThat(empty.query()).isEmpty();

        Path recordsSymlinkData = tempDir.resolve("query-fs-records-symlink");
        Files.createDirectories(recordsSymlinkData);
        Path recordsOutside = tempDir.resolve("query-fs-records-outside");
        Files.createDirectories(recordsOutside.resolve("turns"));
        Files.createSymbolicLink(recordsSymlinkData.resolve("records"), recordsOutside);
        RecordQueryService recordsSymlink = new RecordQueryService(recordsSymlinkData);
        assertThatThrownBy(recordsSymlink::listTurns)
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.UNSAFE_PATH));
        assertThatThrownBy(recordsSymlink::query)
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.UNSAFE_PATH));
        assertThatThrownBy(() -> recordsSymlink.getTurn("missing-turn", "index.md"))
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.UNSAFE_PATH));

        Path turnsSymlinkData = tempDir.resolve("query-fs-turns-symlink");
        Files.createDirectories(turnsSymlinkData.resolve("records"));
        Path turnsOutside = tempDir.resolve("query-fs-turns-outside");
        Files.createDirectories(turnsOutside);
        Files.createSymbolicLink(turnsSymlinkData.resolve("records/turns"), turnsOutside);
        RecordQueryService turnsSymlink = new RecordQueryService(turnsSymlinkData);
        assertThatThrownBy(turnsSymlink::listTurns)
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.UNSAFE_PATH));
        assertThatThrownBy(turnsSymlink::query)
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.UNSAFE_PATH));
        assertThatThrownBy(() -> turnsSymlink.getTurn("missing-turn", "index.md"))
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.UNSAFE_PATH));
    }

    @Test
    void fsOnlyQueryHandlesMissingRecordsAndLegacyPartialOrCompleteProjections()
            throws Exception {
        Path emptyDir = tempDir.resolve("query-empty");
        RecordQueryService empty = new RecordQueryService(emptyDir);
        assertThat(empty.listTurns()).isEmpty();
        assertThat(empty.query()).isEmpty();
        assertThatThrownBy(() -> empty.getTurn("missing-turn", "index.md"))
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code()).isNotBlank());

        Path partialDir = tempDir.resolve("query-legacy-partial");
        Path partialTurn = partialDir.resolve("records/turns/1-partial-turn");
        Files.createDirectories(partialTurn.resolve("players"));
        Files.writeString(partialTurn.resolve("index.md"), """
                # 未完成投影

                - turnId: `partial-turn`
                - turnNo: 1
                - status: OPEN
                """);
        RecordQueryService partial = new RecordQueryService(partialDir);
        assertThat(partial.listTurns()).isEmpty();
        assertThat(partial.query()).isEmpty();
        assertThatThrownBy(() -> partial.getTurn("partial-turn", "index.md"))
                .isInstanceOfSatisfying(RecordProjectionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(RecordProjectionException.RECORD_INCONSISTENT));

        Path completeDir = tempDir.resolve("query-legacy-complete");
        String narrative = "# 旧公共叙事\n\n旧格式可验证正文-70bd\n";
        Path completeTurn = completeDir.resolve("records/turns/1-old-turn");
        Files.createDirectories(completeTurn);
        for (String managed : List.of("players", "actions", "packages", "batches", "posts")) {
            Files.createDirectories(completeTurn.resolve(managed));
        }
        Files.writeString(completeTurn.resolve("public-narrative.md"), narrative);
        String narrativeHash = Hashes.sha256(narrative);
        Files.writeString(completeTurn.resolve("index.md"), """
                # 回合 1 记录索引

                - turnId: `old-turn`
                - turnNo: 1
                - worldVersion: 1
                - status: PUBLISHED
                - revision: 3
                - opensAt: 2026-10-24T00:00:00Z
                - closesAt: 2026-10-24T01:00:00Z
                - batchIds: [batch-old]
                - packageIds: [pkg-old]
                - postIds: [post-old]
                - publicationIds: [pub-old]

                ## Content hashes

                - `public-narrative.md`: `""" + narrativeHash + "`\n");
        RecordQueryService complete = new RecordQueryService(completeDir);

        assertThat(complete.listTurns()).singleElement().satisfies(summary -> {
            assertThat(summary.turnId()).isEqualTo("old-turn");
            assertThat(summary.status()).isEqualTo("PUBLISHED");
            assertThat(summary.batchIds()).containsExactly("batch-old");
        });
        assertThat(complete.query()).extracting(RecordSummary::kind)
                .containsExactlyInAnyOrder("turn", "public-narrative");
        TurnRecordFile file = complete.getTurn("old-turn", "public");
        assertThat(file.content()).isEqualTo(narrative);
        assertThat(file.contentHash()).isEqualTo(narrativeHash);
        assertThat(file.toString()).doesNotContain(completeDir.toString());
    }

    private static String json(String publicNarrative, Map<String, String> privateOutcomes) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("publicNarrative", publicNarrative);
            value.put("privateOutcomes", privateOutcomes);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("测试 JSON 构造失败", failure);
        }
    }
}
