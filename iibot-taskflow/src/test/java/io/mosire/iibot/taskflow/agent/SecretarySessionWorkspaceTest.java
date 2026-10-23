package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SecretarySession 的工作区注入、GM 对话与离线降级行为。 */
class SecretarySessionWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyConstructorStillWorksWithoutWorkspaceTools() {
        AgentTestFixture f = AgentTestFixture.create();
        ScriptedLlm llm = ScriptedLlm.with(LlmResponse.text("旧会话可用"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, Duration.ofMinutes(15),
                Duration.ofMinutes(15));

        assertThat(session.chat("你好")).isEqualTo("旧会话可用");
        assertThat(session.tools()).extracting(tool -> tool.name())
                .doesNotContain(SecretaryWorkspaceTools.LIST,
                        SecretaryWorkspaceTools.READ, SecretaryWorkspaceTools.NOTE);
        assertThat(session.permissions().isToolAllowed(SecretaryWorkspaceTools.LIST)).isFalse();
    }

    @Test
    void injectingStoreAddsWorkspaceToolsAndAllowListEntries() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, store);

        assertThat(session.tools()).extracting(tool -> tool.name())
                .contains(SecretaryWorkspaceTools.LIST,
                        SecretaryWorkspaceTools.READ, SecretaryWorkspaceTools.NOTE);
        assertThat(session.permissions().isToolAllowed(SecretaryWorkspaceTools.LIST)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryWorkspaceTools.READ)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryWorkspaceTools.NOTE)).isTrue();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(SecretaryWorkspaceStore.RESOURCE_NAMESPACE)).isNotNull();
    }

    @Test
    void registryExposesImmutableOrderedSnapshot() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        registry.register(new SecretarySession("player-2", f.p2, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, store));
        registry.register(new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, store));

        assertThat(registry.playerIds()).containsExactly("player-1", "player-2");
        assertThat(registry.all()).extracting(SecretarySession::playerId)
                .containsExactly("player-1", "player-2");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> registry.playerIds().add("player-3"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.playerIds()).containsExactly("player-1", "player-2");
    }

    @Test
    void gmChatUsesGmPromptAndWorkspaceToolsButKeepsPlayerBinding() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("note-1", SecretaryWorkspaceTools.NOTE,
                        Map.of("text", "GM 让秘书记下的内容", "playerId", "player-2")),
                LlmResponse.text("已记录，仅回报给 GM。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, store);

        String reply = session.gmChat("请把这条记到工作区");

        assertThat(reply).isEqualTo("已记录，仅回报给 GM。");
        assertThat(llm.allPromptText())
                .contains("消息来自 GM，不是玩家")
                .contains("请把这条记到工作区");
        assertThat(session.toolInvocations()).hasSize(1);
        assertThat(store.list("player-1", "notes")).hasSize(1);
        assertThat(store.list("player-2", "notes")).isEmpty();
        assertThat(session.toolInvocations().get(0).result().message())
                .doesNotContain("player-2")
                .doesNotContain(store.rootFor("player-1").toString());
    }

    @Test
    void gmChatHasNoPlayerIdOrSendCapabilityInItsSignature() throws Exception {
        assertThat(SecretarySession.class.getMethod("gmChat", String.class)).isNotNull();
        assertThat(SecretarySession.class.getMethods())
                .filteredOn(method -> method.getName().equals("gmChat"))
                .hasSize(1);
        assertThat(SecretarySession.class.getMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("send"));
    }

    @Test
    void fakeLlmFailureDegradesToStableText() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(), f.noopBriefing(), f.director, f.clock, store);

        String reply = session.gmChat("GM 消息");

        assertThat(reply).isEqualTo("秘书暂时无法回复，请稍后重试。");
    }
}
