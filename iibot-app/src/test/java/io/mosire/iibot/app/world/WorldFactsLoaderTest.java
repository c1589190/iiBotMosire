package io.mosire.iibot.app.world;

import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.app.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldFactsLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsAllThreeVisibilityKindsToVersionOne() {
        Path file = TestSupport.writeFacts(tempDir.resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾", "visibility": "PUBLIC"},
                  {"factId": "director-1", "content": "幕后黑手", "visibility": "DIRECTOR_ONLY"},
                  {"factId": "player-1-secret", "content": "你的秘密",
                   "visibility": {"type": "PLAYERS", "playerIds": ["player-1"]}}
                ]}
                """);

        List<WorldFact> facts = WorldFactsLoader.load(file);

        assertThat(facts).hasSize(3);
        assertThat(facts).allSatisfy(fact -> assertThat(fact.worldVersion()).isEqualTo(1));
        assertThat(facts.get(0).visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(facts.get(1).visibility()).isEqualTo(Visibility.DIRECTOR_ONLY);
        assertThat(facts.get(2).visibility().canSeeAsPlayer("player-1")).isTrue();
        assertThat(facts.get(2).visibility().canSeeAsPlayer("player-2")).isFalse();
    }

    @Test
    void rejectsUnknownVisibility() {
        Path file = TestSupport.writeFacts(tempDir.resolve("bad-vis.json"), """
                {"facts": [
                  {"factId": "f1", "content": "x", "visibility": "SECRET_LEVEL"}
                ]}
                """);

        assertThatThrownBy(() -> WorldFactsLoader.load(file))
                .isInstanceOf(WorldFactsException.class)
                .hasMessageContaining("visibility");
    }

    @Test
    void rejectsPlayersWithoutPlayerIds() {
        Path file = TestSupport.writeFacts(tempDir.resolve("empty-players.json"), """
                {"facts": [
                  {"factId": "f1", "content": "x",
                   "visibility": {"type": "PLAYERS", "playerIds": []}}
                ]}
                """);

        assertThatThrownBy(() -> WorldFactsLoader.load(file))
                .isInstanceOf(WorldFactsException.class)
                .hasMessageContaining("playerIds");
    }

    @Test
    void rejectsDuplicateFactIdAndUnknownFields() {
        Path duplicate = TestSupport.writeFacts(tempDir.resolve("duplicate.json"), """
                {"facts": [
                  {"factId": "f1", "content": "x", "visibility": "PUBLIC"},
                  {"factId": "f1", "content": "y", "visibility": "PUBLIC"}
                ]}
                """);
        assertThatThrownBy(() -> WorldFactsLoader.load(duplicate))
                .isInstanceOf(WorldFactsException.class)
                .hasMessageContaining("重复");

        Path unknown = TestSupport.writeFacts(tempDir.resolve("unknown.json"), """
                {"facts": [
                  {"factId": "f1", "content": "x", "visibility": "PUBLIC", "leak": true}
                ]}
                """);
        assertThatThrownBy(() -> WorldFactsLoader.load(unknown))
                .isInstanceOf(WorldFactsException.class)
                .hasMessageContaining("未知字段");
    }
}
