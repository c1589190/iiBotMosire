package io.mosire.iibot.app.publication;

import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostAuditAction;
import io.mosire.iibot.taskflow.post.PostAuditEntry;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPostStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String BODY_SECRET = "绝密正文-不得进入异常日志-7f6d";

    @TempDir
    Path tempDir;

    @Test
    void missingFileLoadsEmptyWithoutCreatingAnything() {
        JsonPostStore store = new JsonPostStore(tempDir);
        assertThat(store.path()).isEqualTo(tempDir.toAbsolutePath().normalize()
                .resolve(JsonPostStore.FILE_NAME));
        assertThat(store.load()).isEqualTo(PostSnapshot.empty());
        assertThat(Files.exists(store.path())).isFalse();
    }

    @Test
    void roundTripPreservesPostsCollectionIdempotencyAndAudit() throws Exception {
        JsonPostStore store = new JsonPostStore(tempDir);
        Post post = new Post("post-1", "player-1", PostVisibility.PUBLIC,
                "北码头", BODY_SECRET, PostStatus.PUBLISHED, 3, NOW, NOW.plusSeconds(2),
                "gm-1", "gm-1", DeliveryState.PENDING);
        PostCommandResult receipt = PostCommandResult.success("post-1", 3);
        PostSnapshot snapshot = PostSnapshot.empty()
                .withPost(post)
                .withCollectionRevision("player-1", 4)
                .withIdempotentResult("publish-key", receipt)
                .withAuditEntry(new PostAuditEntry(PostAuditAction.PUBLISH, "post-1",
                        "gm-1", PostStatus.APPROVED, PostStatus.PUBLISHED, 3,
                        "published", NOW.plusSeconds(1)));

        store.save(snapshot);
        PostSnapshot loaded = store.load();

        assertThat(loaded).isEqualTo(snapshot);
        assertThat(loaded.findPost("post-1").orElseThrow().body()).isEqualTo(BODY_SECRET);
        assertThat(loaded.collectionRevision("player-1")).isEqualTo(4);
        assertThat(loaded.idempotentResults()).containsEntry("publish-key", receipt);
        assertThat(loaded.auditLog()).hasSize(1);
        assertThat(Files.readString(store.path())).contains("\"schemaVersion\":1");
    }

    @Test
    void malformedUnknownFieldTrailingTokenAndSchemaMismatchFailClosedWithoutLeakingBody()
            throws Exception {
        JsonPostStore store = new JsonPostStore(tempDir);
        store.save(snapshot("post-1"));
        String valid = Files.readString(store.path());

        assertCorrupt(store, valid.replaceFirst("\\{", "{\"unexpected\":true,"));
        assertCorrupt(store, valid + "\n{}");
        assertCorrupt(store, "this is not json " + BODY_SECRET);
        assertThatThrownBy(() -> {
            Files.writeString(store.path(),
                    valid.replace("\"schemaVersion\":1", "\"schemaVersion\":999"));
            store.load();
        }).isInstanceOf(PostStoreException.class)
                .satisfies(failure -> {
                    PostStoreException exception = (PostStoreException) failure;
                    assertThat(exception.code()).isEqualTo(PostStoreException.Code.SCHEMA);
                    assertThat(exception.getMessage()).doesNotContain(BODY_SECRET);
                });
    }

    @Test
    void saveAtomicallyReplacesWholeSnapshotAndLeavesNoTemporaryFile() throws Exception {
        JsonPostStore store = new JsonPostStore(tempDir);
        store.save(snapshot("post-1"));
        Path firstPath = store.path();
        String firstContent = Files.readString(firstPath);

        PostSnapshot second = snapshot("post-2");
        store.save(second);

        assertThat(store.load()).isEqualTo(second);
        assertThat(store.load()).isNotEqualTo(snapshot("post-1"));
        assertThat(Files.readString(store.path())).isNotEqualTo(firstContent);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(JsonPostStore.FILE_NAME);
        }
        if (Files.getFileStore(tempDir).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(store.path());
            assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void saveFailureLeavesExistingCompleteSnapshotUntouched() throws Exception {
        JsonPostStore store = new JsonPostStore(tempDir);
        store.save(snapshot("post-1"));
        Path parent = store.path().getParent();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(parent);
        try {
            Files.setPosixFilePermissions(parent, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assertThatThrownBy(() -> store.save(snapshot("post-2")))
                    .isInstanceOf(PostStoreException.class)
                    .satisfies(failure -> assertThat(
                            ((PostStoreException) failure).code())
                            .isIn(PostStoreException.Code.SAVE, PostStoreException.Code.IO));
        } finally {
            Files.setPosixFilePermissions(parent, original);
        }
        assertThat(store.load()).isEqualTo(snapshot("post-1"));
    }

    private static PostSnapshot snapshot(String postId) {
        Post post = new Post(postId, "player-1", PostVisibility.PRIVATE,
                "标题-" + postId, BODY_SECRET + "-" + postId, PostStatus.PENDING_APPROVAL,
                1, NOW, NOW, null, null, null);
        return PostSnapshot.empty().withPost(post)
                .withCollectionRevision("player-1", 1);
    }

    private static void assertCorrupt(JsonPostStore store, String content) throws Exception {
        Files.writeString(store.path(), content);
        assertThatThrownBy(store::load)
                .isInstanceOf(PostStoreException.class)
                .satisfies(failure -> {
                    PostStoreException exception = (PostStoreException) failure;
                    assertThat(exception.code()).isEqualTo(PostStoreException.Code.CORRUPT);
                    assertThat(exception.getMessage()).doesNotContain(BODY_SECRET);
                });
    }
}
