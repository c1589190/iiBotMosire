package io.mosire.iibot.taskflow.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全局共享 Skill 区：目录、Docs 读写、覆盖策略、路径围栏与软链 fail-closed。 */
class SharedSkillStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void openCreatesPublicAndSkillsRootsAndIsIdempotent() {
        SharedSkillStore first = SharedSkillStore.open(tempDir);

        Path root = first.rootForDiagnostics();
        assertThat(root).isDirectory();
        assertThat(root.resolve("public")).isDirectory();
        assertThat(root.resolve("skills")).isDirectory();
        assertThat(first.list("")).containsExactlyInAnyOrder("public", "skills");

        SharedSkillStore second = SharedSkillStore.open(tempDir);
        assertThat(second.rootForDiagnostics()).isEqualTo(root);
        assertThat(second.list("")).containsExactlyInAnyOrder("public", "skills");
    }

    @Test
    void writeCreateReadListAndReplace() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);

        SharedSkillStore.WriteResult created = store.writeGm(
                "public/notice.md", "第一条公共公告", false);
        assertThat(created.path()).isEqualTo("public/notice.md");
        assertThat(created.created()).isTrue();
        assertThat(created.replaced()).isFalse();
        assertThat(created.status()).isEqualTo("created");
        assertThat(created.contentHash()).hasSize(64);
        assertThat(store.read("public/notice.md")).contains("第一条公共公告\n");
        assertThat(store.list("public")).containsExactly("public/notice.md");

        SharedSkillStore.WriteResult replaced = store.writeGm(
                "public/notice.md", "替换后的公告", true);
        assertThat(replaced.created()).isFalse();
        assertThat(replaced.replaced()).isTrue();
        assertThat(replaced.status()).isEqualTo("replaced");
        assertThat(store.read("public/notice.md")).contains("替换后的公告\n");
        assertThat(store.read("public/notice.md").orElseThrow()).doesNotContain("第一条公共公告");

        SharedSkillStore.WriteResult skill = store.writeGm(
                "skills/secretary-guide.md", "响应指南", false);
        assertThat(skill.created()).isTrue();
        assertThat(store.list("skills")).containsExactly("skills/secretary-guide.md");
    }

    @Test
    void defaultWriteRefusesOverwriteAndKeepsOldContent() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        store.writeGm("skills/guide.md", "旧指南", false);

        assertThatThrownBy(() -> store.writeGm("skills/guide.md", "新指南", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝覆盖");
        assertThat(store.read("skills/guide.md").orElseThrow()).contains("旧指南");
        assertThat(store.read("skills/guide.md").orElseThrow()).doesNotContain("新指南");
    }

    @Test
    void missingReadIsEmptyAndUnknownDirectoryFailsClosed() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);

        assertThat(store.read("public/missing.md")).isEmpty();
        assertThat(store.read("skills/missing.md")).isEmpty();
        assertThatThrownBy(() -> store.list("public/missing-dir"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlongContentIsRejectedWithoutEchoingBody() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        String secret = "secret-token-value-";
        String overlong = secret.repeat((SharedSkillStore.MAX_CONTENT_BYTES / secret.length()) + 1);

        assertThatThrownBy(() -> store.writeGm("public/big.md", overlong, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret)
                .hasMessageNotContaining("ssss");
        assertThat(store.read("public/big.md")).isEmpty();
    }

    @Test
    void absoluteDotDotDotBackslashEmptyTildeDriveAndForeignRootsAreRejected() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);

        for (String path : List.of(
                "/public/x.md",
                "public/../skills/x.md",
                "public/./x.md",
                "public//x.md",
                "public\\x.md",
                "~secret.md",
                "C:/windows/x.md",
                "other/x.md")) {
            assertThatThrownBy(() -> store.read(path))
                    .as("read path %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.writeGm(path, "x", false))
                    .as("write path %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.read("public"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeGm("skills", "x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list("public/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void symlinkEscapeIsRejectedOnReadListAndWrite() throws Exception {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.md"), "outside secret");
        Path publicRoot = store.rootForDiagnostics().resolve("public");
        Files.createSymbolicLink(publicRoot.resolve("escape.md"), secret);
        Files.createSymbolicLink(publicRoot.resolve("linked"), outside);

        assertThatThrownBy(() -> store.read("public/escape.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("outside secret")
                .hasMessageNotContaining(outside.toString());
        assertThatThrownBy(() -> store.list("public/linked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(outside.toString());
        assertThatThrownBy(() -> store.writeGm("public/linked/new.md", "x", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(outside.toString());
        assertThat(outside.resolve("new.md")).doesNotExist();
    }

    @Test
    void symlinkedSharedRootIsRejectedAtOpen() throws Exception {
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path outside = Files.createDirectory(tempDir.resolve("outside-root"));
        Files.createSymbolicLink(data.resolve("shared-skills"), outside);

        assertThatThrownBy(() -> SharedSkillStore.open(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(outside.toString());
        try (var entries = Files.list(outside)) {
            assertThat(entries).isEmpty();
        }
    }
}
