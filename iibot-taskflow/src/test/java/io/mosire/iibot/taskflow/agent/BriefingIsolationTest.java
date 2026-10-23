package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.taskflow.InfoBrief;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** request_brief 只能看到当前玩家可见事实；不可见事实不得进入 prompt 或工具结果。 */
class BriefingIsolationTest {

    @Test
    void requestBriefOnlyReferencesPlayerVisibleFacts() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(
                new WorldFact("public-1", 1, "PUBLIC_港口钟声", Visibility.PUBLIC),
                new WorldFact("a-secret", 1, "A_ONLY_码头暗门", Visibility.forPlayers(Set.of("player-1"))),
                new WorldFact("b-secret", 1, "B_ONLY_王宫密令", Visibility.forPlayers(Set.of("player-2"))));
        f.openTurn();

        ScriptedLlm briefLlm = ScriptedLlm.with(
                LlmResponse.text("BRIEF_CONTENT：港口钟声与 A 的码头暗门都值得调查。"));
        ScriptedLlm secretaryLlm = ScriptedLlm.with(
                LlmResponse.toolCall("brief-call", RequestBriefTool.NAME,
                        Map.of("question", "港口有什么值得调查的？")),
                LlmResponse.text("我已经把审查后的简报给你了。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                secretaryLlm, new LlmBriefingService(briefLlm), f.director, f.clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15));

        String answer = session.chat("帮我打听港口线索");

        assertThat(answer).isEqualTo("我已经把审查后的简报给你了。");
        assertThat(briefLlm.requests()).hasSize(1);
        String briefPrompt = briefLlm.allPromptText();
        assertThat(briefPrompt)
                .contains("PUBLIC_港口钟声")
                .contains("A_ONLY_码头暗门")
                .doesNotContain("B_ONLY_王宫密令");

        assertThat(secretaryLlm.allPromptText())
                .contains("BRIEF_CONTENT")
                .doesNotContain("B_ONLY_王宫密令");

        List<InfoBrief> briefs = f.engine.briefsForPlayer("player-1");
        assertThat(briefs).hasSize(1);
        Set<String> visibleIds = f.engine.visibleFactsFor("player-1").stream()
                .map(WorldFact::factId).collect(Collectors.toSet());
        assertThat(briefs.get(0).sourceFactIds()).isSubsetOf(visibleIds);
        assertThat(briefs.get(0).sourceFactIds())
                .contains("public-1", "a-secret")
                .doesNotContain("b-secret");

        assertThat(session.toolInvocations()).hasSize(1);
        String toolMessage = session.toolInvocations().get(0).result().message();
        assertThat(toolMessage)
                .contains("BRIEF_CONTENT")
                .contains("public-1")
                .doesNotContain("B_ONLY_王宫密令");
    }
}
