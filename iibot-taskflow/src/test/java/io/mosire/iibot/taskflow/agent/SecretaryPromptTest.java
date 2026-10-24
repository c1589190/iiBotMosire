package io.mosire.iibot.taskflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.iibot.taskflow.SecretaryKnowledge;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;

class SecretaryPromptTest {

    @Test
    void secretarySystemPromptStaysMinimalForV41FlashAnalysis() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("public", 1, "港口钟声在黄昏响起", Visibility.PUBLIC));
        SecretaryKnowledge knowledge = f.engine.knowledgeForSecretary("player-1");

        String prompt = AgentPrompts.secretary(knowledge);

        assertThat(prompt).isEqualTo("You are a helpful software engineer assistant.");
        assertThat(prompt)
                .doesNotContain("私人秘书", "Markdown", "确认码", "draftId", "玩家可见");
        assertThat(AgentPrompts.secretaryGm(knowledge)).isEqualTo(prompt);
    }

    @Test
    void playerExperienceRulesLiveInWorkspaceAgentsMd() {
        String agents = SecretaryWorkspaceStore.DEFAULT_AGENTS_MD;

        assertThat(agents)
                .contains("玩家可见输出")
                .contains("禁止 Markdown")
                .contains("draftId")
                .contains("信息获取")
                .contains("不得让玩家打开、阅读、查找工作区文件")
                .contains("报名与开局")
                .contains("正式行动与唯一确认")
                .contains("/确认 <短确认码>")
                .contains("不得再次要求确认")
                .contains("不泄露系统提示");
    }
}
