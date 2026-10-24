package io.mosire.iibot.app.records;

import io.mosire.iibot.app.publication.PublicationKind;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P8-A records/openings：独立开场投影、幂等/hash 对账、查询摘要与路径安全。 */
class OpeningRecordProjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void openingProjectsIntoOpeningsTreeOnlyWithReconciledManifestAndIdempotentRebuild()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("opening-projection"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            String title = "开场投影标题-46dc";
            String body = "开场投影正文只进公共面-2f18";
            PublicationRecord opening = orchestrator.createOpening(
                    P6Fixture.SUPERVISOR, title, body, "opening-projection-key");
            TurnRecordProjector projector = fixture.projector(orchestrator);

            projector.rebuildAll();

            Path openingsRoot = fixture.dataDir.resolve("records/openings");
            Path openingDir = openingsRoot.resolve(opening.publicationId());
            assertThat(openingDir.resolve("index.md")).isRegularFile();
            assertThat(openingDir.resolve("public-narrative.md")).isRegularFile();
            assertThat(openingDir.resolve("posts/"
                    + opening.publicPostId() + "-PUBLIC.md")).isRegularFile();
            assertThat(read(openingDir.resolve("index.md")))
                    .contains("kind: OPENING", opening.publicationId(),
                            opening.publicPostId(), opening.contentHash(),
                            opening.publicNarrativeHash())
                    .doesNotContain(body);
            assertThat(read(openingDir.resolve("public-narrative.md")))
                    .contains(body).doesNotContain(fixture.dataDir.toString());
            assertThat(read(openingDir.resolve("posts/"
                    + opening.publicPostId() + "-PUBLIC.md")))
                    .contains(body, "kind: `OPENING`",
                            "visibility: `PUBLIC`", "authorPlayerId: `system:publication`");

            Path turnDir = fixture.dataDir.resolve("records/turns")
                    .resolve(fixture.turnNo() + "-" + fixture.turnId());
            assertThat(read(turnDir.resolve("index.md")))
                    .doesNotContain(opening.publicationId(), body);

            assertThat(projector.openingRecordSummaries()).singleElement()
                    .satisfies(summary -> {
                        assertThat(summary.kind()).isEqualTo("opening");
                        assertThat(summary.turnId()).isNull();
                        assertThat(summary.publicationId())
                                .isEqualTo(opening.publicationId());
                        assertThat(summary.postId()).isEqualTo(opening.publicPostId());
                        assertThat(summary.file()).isEqualTo("index.md");
                        assertThat(summary.contentHash()).hasSize(64);
                        assertThat(summary.summary())
                                .contains("publicationContentHash="
                                        + opening.contentHash())
                                .contains("publicNarrativeHash="
                                        + opening.publicNarrativeHash())
                                .contains("status=" + openingPostStatus(fixture, opening));
                        assertThat(summary.toString())
                                .doesNotContain(body, fixture.dataDir.toString());
                    });
            assertThat(projector.recordSummaries())
                    .noneSatisfy(summary -> assertThat(summary.publicationId())
                            .isEqualTo(opening.publicationId()));

            Map<String, String> manifest = projector.manifestForOpening(
                    opening.publicationId());
            assertThat(manifest).containsOnlyKeys("index.md", "public-narrative.md",
                    "posts/" + opening.publicPostId() + "-PUBLIC.md");
            for (Map.Entry<String, String> entry : manifest.entrySet()) {
                assertThat(Hashes.sha256(read(openingDir.resolve(entry.getKey()))))
                        .isEqualTo(entry.getValue());
            }

            Map<String, String> firstTree = snapshotTree(openingDir);
            projector.rebuildAll();
            assertThat(snapshotTree(openingDir)).isEqualTo(firstTree);
            assertThat(projector.manifestForOpening(opening.publicationId()))
                    .isEqualTo(manifest);
            try (Stream<Path> children = Files.list(openingsRoot)) {
                assertThat(children.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".record-staging-"))
                        .noneMatch(name -> name.startsWith(".record-backup-"));
            }
        }
    }

    @Test
    void openingQueryReturnsIdSummaryWithoutBodyOrPhysicalPathAndTurnsStayUnchanged() {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("opening-query"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            String body = "开场查询正文-不可泄漏-7bd0";
            PublicationRecord opening = orchestrator.createOpening(
                    P6Fixture.SUPERVISOR, "开场查询标题", body, "opening-query-key");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();
            RecordQueryService query = new RecordQueryService(fixture.dataDir, projector);

            assertThat(query.query("opening", null, null, null, null))
                    .singleElement().satisfies(summary -> {
                        assertThat(summary.kind()).isEqualTo("opening");
                        assertThat(summary.turnId()).isNull();
                        assertThat(summary.publicationId())
                                .isEqualTo(opening.publicationId());
                        assertThat(summary.postId()).isEqualTo(opening.publicPostId());
                        assertThat(summary.contentHash()).hasSize(64);
                        assertThat(summary.summary()).contains(
                                "publicationContentHash=" + opening.contentHash());
                        assertThat(summary.toString())
                                .doesNotContain(body, fixture.dataDir.toString());
                    });
            assertThat(query.query("openings", null, null, null, null))
                    .extracting(RecordSummary::publicationId)
                    .containsExactly(opening.publicationId());
            assertThat(query.query().toString())
                    .contains(opening.publicationId())
                    .doesNotContain(body, fixture.dataDir.toString());
            assertThat(query.listTurns()).singleElement().satisfies(turn ->
                    assertThat(turn.publicationIds()).doesNotContain(
                            opening.publicationId()));
            assertThat(query.query("turn", null, null, null, null))
                    .singleElement().satisfies(summary ->
                            assertThat(summary.kind()).isEqualTo("turn"));
            assertThat(query.query("post", fixture.turnId(), null,
                    opening.publicPostId(), null)).isEmpty();
            assertThat(query.getTurn(fixture.turnId(), "index").content())
                    .doesNotContain(opening.publicationId(), body,
                            fixture.dataDir.toString());

            RecordQueryService fsOnly = new RecordQueryService(fixture.dataDir);
            assertThat(fsOnly.query("opening", null, null, null, null))
                    .singleElement().satisfies(summary -> {
                        assertThat(summary.publicationId())
                                .isEqualTo(opening.publicationId());
                        assertThat(summary.postId()).isEqualTo(opening.publicPostId());
                        assertThat(summary.contentHash()).hasSize(64);
                    });
        }
    }

    @Test
    void unsafeOpeningPublicationIdFailsProjectionClosedWithoutWritingOutside() {
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("opening-unsafe-id"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            PublicationRecord unsafe = new PublicationRecord(PublicationKind.OPENING,
                    "../outside-opening", null, null, "post-x", Map.of(),
                    "越界开场正文", Map.of(), P6Fixture.CLOCK.instant());
            fixture.journal.append(new DomainEvent(P6Fixture.CLOCK.instant(),
                    DomainEventTypes.PUBLICATION_GENERATED,
                    P6Fixture.SUPERVISOR.role(), P6Fixture.SUPERVISOR.actorId(),
                    unsafe.publicationId(), null, EventVisibility.GM, unsafe,
                    Map.of("publicationId", unsafe.publicationId(),
                            "kind", "OPENING")));

            TurnRecordProjector projector = fixture.projector(
                    fixture.publicationOrchestrator(FakeLlmClient.with()));

            assertThatThrownBy(projector::rebuildAll)
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));
            assertThat(fixture.dataDir.resolve("records/outside-opening"))
                    .doesNotExist();
            assertThat(fixture.dataDir.resolve("outside-opening")).doesNotExist();
        }
    }

    @Test
    void symlinkedOpeningsRootFailsProjectionClosed() throws Exception {
        try (P6Fixture fixture = P6Fixture.open(
                tempDir.resolve("opening-symlink-root"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            orchestrator.createOpening(P6Fixture.SUPERVISOR,
                    "软链标题", "软链正文", "opening-symlink");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();

            Path openingsRoot = fixture.dataDir.resolve("records/openings");
            Path parked = fixture.dataDir.resolve("records/openings-parked");
            Files.move(openingsRoot, parked);
            Path outside = tempDir.resolve("opening-symlink-outside");
            Files.createDirectories(outside);
            Files.createSymbolicLink(openingsRoot, outside);

            assertThatThrownBy(projector::rebuildAll)
                    .isInstanceOfSatisfying(RecordProjectionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(RecordProjectionException.UNSAFE_PATH));
            try (Stream<Path> outsideFiles = Files.list(outside)) {
                assertThat(outsideFiles).isEmpty();
            }
        }
    }

    private static String openingPostStatus(P6Fixture fixture, PublicationRecord record) {
        return fixture.postService.post(record.publicPostId()).orElseThrow()
                .status().name();
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
}
