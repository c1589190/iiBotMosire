package io.mosire.iibot.app.world;

import io.mosire.docs.DocEntry;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldDocsTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAndReadsBibleAndAppendsAuditLog() throws Exception {
        List<WorldFact> facts = List.of(
                new WorldFact("public-1", 1, "港口迷雾", Visibility.PUBLIC),
                new WorldFact("director-1", 1, "幕后黑手", Visibility.DIRECTOR_ONLY));

        WorldDocs docs = WorldDocs.open(tempDir, "prompt-v1",
                WorldDocs.publicSummary(facts), facts);
        try {
            String bible = docs.bibleText();
            assertThat(bible).contains("# 世界圣经").contains("prompt-v1")
                    .contains("港口迷雾").doesNotContain("幕后黑手");
            Path physical = tempDir.resolve("world").resolve("bible.md");
            assertThat(physical).isRegularFile();
            assertThat(Files.readString(physical)).isEqualTo(bible);

            docs.appendAudit("test", "append works");
            List<DocEntry> firstRead = docs.readAudit();
            assertThat(firstRead).extracting(DocEntry::value).contains("append works");
        } finally {
            docs.close();
        }

        // close 是幂等且会追加 shutdown；重新打开不会覆盖既有 bible。
        docs.close();
        WorldDocs reopened = WorldDocs.open(tempDir, "prompt-v1",
                WorldDocs.publicSummary(facts), facts);
        try {
            assertThat(reopened.bibleText()).contains("港口迷雾");
            assertThat(reopened.readAudit()).extracting(entry -> String.valueOf(entry.value()))
                    .anyMatch(value -> value.contains("host shutdown"));
        } finally {
            reopened.close();
        }
    }
}
