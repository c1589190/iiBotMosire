package io.mosire.iibot.taskflow.workspace;

import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P3 GM 私有文档区：逻辑根/路径隔离、覆盖语义、权限与 best-effort 审计事件。 */
class GmPrivateDocsTest {

    @TempDir
    Path tempDir;

    @Test
    void writeListGetReplacePermissionsAndAuditEventShape() throws Exception {
        List<DomainEvent> events = new ArrayList<>();
        GmPrivateDocs docs = GmPrivateDocs.open(tempDir, event -> {
            events.add(event);
            return events.size();
        });

        GmPrivateDocs.WriteResult created = docs.write(
                "world/summary.md", "# 世界\n港口迷雾", false);
        assertThat(created.created()).isTrue();
        assertThat(created.path()).isEqualTo("world/summary.md");
        assertThat(created.contentHash()).hasSize(64);

        List<GmPrivateDocs.Entry> listed = docs.list("world");
        assertThat(listed).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("world/summary.md");
            assertThat(entry.size()).isEqualTo(created.size());
        });
        assertThat(docs.list("world").toString()).doesNotContain(tempDir.toString());

        GmPrivateDocs.ReadResult read = docs.read("world/summary.md").orElseThrow();
        assertThat(read.content()).isEqualTo("# 世界\n港口迷雾");
        assertThat(read.contentHash()).isEqualTo(created.contentHash());

        assertThatThrownBy(() -> docs.write("world/summary.md", "覆盖", false))
                .isInstanceOfSatisfying(GmPrivateDocsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(GmPrivateDocsException.CONFLICT));
        GmPrivateDocs.WriteResult replaced = docs.write(
                "world/summary.md", "# 世界\n覆盖后的正文", true);
        assertThat(replaced.replaced()).isTrue();
        assertThat(docs.read("world/summary.md").orElseThrow().content())
                .isEqualTo("# 世界\n覆盖后的正文");

        Path root = docs.rootForDiagnostics();
        assertThat(root).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("gm-private"));
        Path file = root.resolve("world/summary.md");
        assertThat(Files.isRegularFile(file)).isTrue();
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(root))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }

        assertThat(events).extracting(DomainEvent::eventType)
                .containsExactly(DomainEventTypes.GM_DOC_CREATED,
                        DomainEventTypes.GM_DOC_UPDATED);
        DomainEvent createdEvent = events.get(0);
        Map<String, Object> createdContent = contentMap(createdEvent);
        assertThat(createdContent)
                .containsEntry("path", "world/summary.md")
                .containsEntry("content", "# 世界\n港口迷雾")
                .containsKeys("contentHash", "size", "replace");
        assertThat(createdEvent.summary()).doesNotContainKey("content");
        assertThat(createdEvent.summary().toString())
                .doesNotContain("# 世界").doesNotContain("港口迷雾");
        assertThat(contentMap(events.get(1)))
                .containsEntry("content", "# 世界\n覆盖后的正文");
        assertThat(events.get(1).summary().toString())
                .doesNotContain("覆盖后的正文");
    }

    @Test
    void pathTraversalAbsoluteBackslashIllegalRootAndSymlinkAreRejected() throws Exception {
        GmPrivateDocs docs = GmPrivateDocs.open(tempDir);

        for (String invalid : List.of(
                "../escape.md", "/etc/passwd", "world/../escape.md",
                "world\\escape.md", "other/escape.md", "world/", "world//x.md")) {
            assertThatThrownBy(() -> docs.write(invalid, "x", false))
                    .as("path " + invalid)
                    .isInstanceOfSatisfying(GmPrivateDocsException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(GmPrivateDocsException.INVALID_ARGUMENTS));
        }
        assertThatThrownBy(() -> docs.list("world/sub"))
                .isInstanceOfSatisfying(GmPrivateDocsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(GmPrivateDocsException.INVALID_ARGUMENTS));

        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "外部正文");
        Path link = docs.rootForDiagnostics().resolve("world/link.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            Assumptions.abort("当前文件系统不支持测试用软链");
        }
        assertThatThrownBy(() -> docs.read("world/link.md"))
                .isInstanceOfSatisfying(GmPrivateDocsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(GmPrivateDocsException.INVALID_ARGUMENTS));
        assertThatThrownBy(() -> docs.write("world/link.md", "x", true))
                .isInstanceOfSatisfying(GmPrivateDocsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(GmPrivateDocsException.INVALID_ARGUMENTS));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contentMap(DomainEvent event) {
        return (Map<String, Object>) event.content();
    }

    @Test
    void oversizedOrInvalidUtf8ContentFailsClosedAndAuditFailureDoesNotFakeWriteFailure()
            throws Exception {
        GmPrivateDocs docs = GmPrivateDocs.open(tempDir);
        String oversized = "x".repeat(GmPrivateDocs.MAX_CONTENT_BYTES + 1);
        assertThatThrownBy(() -> docs.write("notes/big.txt", oversized, false))
                .isInstanceOfSatisfying(GmPrivateDocsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(GmPrivateDocsException.INVALID_ARGUMENTS));

        Path invalidUtf8 = docs.rootForDiagnostics().resolve("notes/bad.txt");
        Files.write(invalidUtf8, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertThatThrownBy(() -> docs.read("notes/bad.txt"))
                .isInstanceOfSatisfying(GmPrivateDocsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(GmPrivateDocsException.UNAVAILABLE));

        // 审计 journal 失败是 best-effort：文件写入仍成功返回，不回滚也不伪装失败。
        GmPrivateDocs docsWithFailingAudit = GmPrivateDocs.open(tempDir,
                event -> {
                    throw new IllegalStateException("模拟 GM_DOC 事件写失败");
                });
        GmPrivateDocs.WriteResult written = docsWithFailingAudit.write(
                "settings/audit-failure.md", "已落盘正文", false);
        assertThat(written.created()).isTrue();
        assertThat(docsWithFailingAudit.read("settings/audit-failure.md"))
                .hasValueSatisfying(result -> assertThat(result.content())
                        .isEqualTo("已落盘正文"));
    }
}
