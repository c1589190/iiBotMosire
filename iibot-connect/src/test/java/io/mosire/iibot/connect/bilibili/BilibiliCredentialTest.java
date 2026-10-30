package io.mosire.iibot.connect.bilibili;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BilibiliCredentialTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsOwnerOnlyCredentialWithoutExposingSecrets() throws Exception {
        Path file = tempDir.resolve("bili.json");
        Files.writeString(file, """
                {"sessdata":"sess-value","biliJct":"csrf-value",
                 "buvid3":"buvid-value","dedeUserId":"123456"}
                """);
        Files.setPosixFilePermissions(file,
                PosixFilePermissions.fromString("rw-------"));

        BilibiliCredential credential = BilibiliCredential.load(file);

        assertThat(credential.authenticated()).isTrue();
        assertThat(credential.dedeUserId()).isEqualTo("123456");
        assertThat(credential.toString()).doesNotContain("sess-value", "csrf-value");
        assertThat(credential.cookieHeader())
                .contains("SESSDATA=sess-value", "bili_jct=csrf-value")
                .doesNotContain("csrf-value; csrf");
    }

    @Test
    void rejectsGroupOrWorldReadableCredential() throws Exception {
        Path file = tempDir.resolve("bili.json");
        Files.writeString(file, """
                {"SESSDATA":"sess-value","bili_jct":"csrf-value"}
                """);
        Files.setPosixFilePermissions(file,
                PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> BilibiliCredential.load(file))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("0600");
    }
}
