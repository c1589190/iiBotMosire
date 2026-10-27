package io.mosire.iibot.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonConfigBundleTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesObjectsAndReplacesArraysInLexicalOrder() throws Exception {
        Files.writeString(tempDir.resolve("00-base.json"), """
                {
                  "server": {"host": "127.0.0.1", "port": 1},
                  "channels": ["qq"],
                  "nested": {"left": 1}
                }
                """);
        Files.writeString(tempDir.resolve("10-override.json"), """
                {
                  "server": {"port": 2},
                  "channels": ["qq", "other"],
                  "nested": {"right": 2}
                }
                """);

        JsonConfigBundle.Bundle bundle = JsonConfigBundle.load(List.of(tempDir));

        assertThat(bundle.root().get("server").get("host").asText()).isEqualTo("127.0.0.1");
        assertThat(bundle.root().get("server").get("port").asInt()).isEqualTo(2);
        assertThat(bundle.root().get("channels")).hasSize(2);
        assertThat(bundle.root().get("nested").get("left").asInt()).isEqualTo(1);
        assertThat(bundle.root().get("nested").get("right").asInt()).isEqualTo(2);
        assertThat(bundle.baseDir()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(bundle.files()).extracting(path -> path.getFileName().toString())
                .containsExactly("00-base.json", "10-override.json");
    }

    @Test
    void rejectsSourcesFromDifferentDirectories() throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("first"));
        Path second = Files.createDirectories(tempDir.resolve("second"));
        Path firstFile = Files.writeString(first.resolve("a.json"), "{\"a\":1}");
        Path secondFile = Files.writeString(second.resolve("b.json"), "{\"b\":2}");

        assertThatThrownBy(() -> JsonConfigBundle.load(List.of(firstFile, secondFile)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("同一目录");
    }

    @Test
    void rejectsDuplicateKeysInsideOneFile() throws Exception {
        Path file = Files.writeString(tempDir.resolve("bad.json"), """
                {"a": 1, "a": 2}
                """);

        assertThatThrownBy(() -> JsonConfigBundle.load(List.of(file)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("重复字段");
    }

    @Test
    void emptyDirectoryFailsClosed() throws Exception {
        assertThatThrownBy(() -> JsonConfigBundle.load(List.of(tempDir)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("没有 *.json");
    }
}
