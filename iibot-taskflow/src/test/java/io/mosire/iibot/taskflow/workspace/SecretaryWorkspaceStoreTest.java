package io.mosire.iibot.taskflow.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 秘书工作区基础层：目录、Docs 读写、路径围栏与软链 fail-closed。 */
class SecretaryWorkspaceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void openCreatesFourRootsForEveryPlayerAndIsIdempotent() throws Exception {
        SecretaryWorkspaceStore first = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));

        for (String playerId : List.of("player-1", "player-2")) {
            Path root = first.rootFor(playerId);
            assertThat(root).isDirectory();
            for (String directory : SecretaryWorkspaceStore.ROOT_DIRECTORIES) {
                assertThat(root.resolve(directory)).isDirectory();
            }
            assertThat(first.list(playerId, "")).containsExactlyInAnyOrder(
                    "public", "player", "gm", "notes");
        }

        SecretaryWorkspaceStore second = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        assertThat(second.rootFor("player-1")).isEqualTo(first.rootFor("player-1"));
        assertThat(second.list("player-1", "")).containsExactlyInAnyOrder(
                "public", "player", "gm", "notes");
    }

    @Test
    void gmMaterialCanBeWrittenToGmAndPlayerAndReadBack() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));

        store.writeGmMaterial("player-1", "gm/order.md", "GM 给秘书的指令\n第二行");
        store.writeGmMaterial("player-1", "player/brief.md", "玩家可见简报");

        assertThat(store.read("player-1", "gm/order.md"))
                .contains("GM 给秘书的指令\n第二行\n");
        assertThat(store.read("player-1", "player/brief.md"))
                .contains("玩家可见简报\n");
        assertThat(store.list("player-1", "gm")).containsExactly("gm/order.md");
        assertThat(store.list("player-1", "player")).containsExactly("player/brief.md");
        assertThat(store.list("player-1", "public")).isEmpty();
    }

    @Test
    void noteOnlyWritesUniqueFilesUnderNotes() throws Exception {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));

        store.appendNote("player-1", "第一条笔记");
        store.appendNote("player-1", "第二条笔记");

        List<String> notes = store.list("player-1", "notes");
        assertThat(notes).hasSize(2);
        assertThat(notes).allSatisfy(path -> {
            assertThat(path).startsWith("notes/note-");
            assertThat(path).endsWith(".md");
        });
        assertThat(store.read("player-1", notes.get(0))).isPresent();
        assertThat(store.read("player-1", notes.get(1))).isPresent();
        assertThat(store.list("player-1", "public")).isEmpty();
        assertThat(store.list("player-1", "gm")).isEmpty();
        assertThat(store.list("player-1", "player")).isEmpty();
        try (var files = Files.list(store.rootFor("player-1").resolve("notes"))) {
            assertThat(files.count()).isEqualTo(2);
        }
    }

    @Test
    void readMissingFileReturnsEmpty() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));

        assertThat(store.read("player-1", "public/missing.md")).isEmpty();
        assertThat(store.read("player-1", "gm/missing.md")).isEmpty();
    }

    @Test
    void unknownPlayerAndPlayerIdPathEscapesFailClosed() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));

        assertThatThrownBy(() -> store.list("player-2", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("player-2", "public/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.appendNote("player-2", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeGmMaterial("player-2", "gm/x.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretaryWorkspaceStore.open(tempDir,
                List.of("../player-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absoluteDotDotBackslashEmptySegmentsAndForeignRootsAreRejected() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));

        assertThatThrownBy(() -> store.read("player-1", "/public/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("player-1", "gm/../public/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("player-1", "gm\\x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list("player-1", "gm//"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("player-1", "other/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list("player-1", "C:/windows/x"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.writeGmMaterial("player-1", "public/x.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeGmMaterial("player-1", "notes/x.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeGmMaterial("player-1", "gm", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlongContentIsRejectedWithoutEchoingContent() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        String secret = "secret-token-value-";
        String overlongNote = secret.repeat(
                (SecretaryWorkspaceStore.MAX_NOTE_CHARS / secret.length()) + 1);
        String overlongMaterial = "x".repeat(SecretaryWorkspaceStore.MAX_CONTENT_CHARS + 1);

        assertThatThrownBy(() -> store.appendNote("player-1", overlongNote))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-token-value");
        assertThatThrownBy(() -> store.writeGmMaterial("player-1", "gm/big.md",
                overlongMaterial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("xxxx");
    }

    @Test
    void symlinkEscapeIsRejectedOnReadAndWrite() throws Exception {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.md"), "outside secret");

        Path gm = store.rootFor("player-1").resolve("gm");
        Files.createSymbolicLink(gm.resolve("escape.md"), secret);
        Files.createSymbolicLink(gm.resolve("linked"), outside);

        assertThatThrownBy(() -> store.read("player-1", "gm/escape.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("outside secret");
        assertThatThrownBy(() -> store.writeGmMaterial("player-1", "gm/linked/new.md", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(outside.toString());
    }

    @Test
    void symlinkNotesRootIsRejected() throws Exception {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        Path notes = store.rootFor("player-1").resolve("notes");
        Path outside = Files.createDirectories(tempDir.resolve("outside-notes"));
        Files.delete(notes);
        Files.createSymbolicLink(notes, outside);

        assertThatThrownBy(() -> store.appendNote("player-1", "不能逃逸"))
                .isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(outside)) {
            assertThat(files).isEmpty();
        }
    }
}
