package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.Visibility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 硬确认只接受同一私聊 QQ 映射；自然语言与群聊不能提交。 */
class InteractionConfirmationTest {

    @Test
    void onlyMappedPlayerPrivateSlashConfirmCanSubmit() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("pub", 1, "公开线索", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraftDirect(f.p1, "确认隔离测试");

        ScriptedLlm p1Llm = ScriptedLlm.with(LlmResponse.text("自然语言“确认”不会提交，请输入 /确认 <draftId>。"));
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        registry.register(new SecretarySession("player-1", f.p1, f.engine, p1Llm,
                f.noopBriefing(), f.director, f.clock, Duration.ofMinutes(15),
                Duration.ofMinutes(15)));
        registry.register(new SecretarySession("player-2", f.p2, f.engine,
                ScriptedLlm.with(LlmResponse.text("unused")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15)));
        AdjudicationService adjudication = new AdjudicationService(f.engine,
                ScriptedLlm.with(LlmResponse.text("unused")));
        InteractionTaskflowService service = new InteractionTaskflowService(f.engine,
                f.identities(), registry, adjudication, () -> f.loadedFacts,
                "group-public", f.clock);

        List<InteractionReply> natural = service.handle(privateMessage("qq-p1", "确认", "m-natural"));
        assertThat(natural).hasSize(1);
        assertThat(f.turn().decisions()).isEmpty();

        List<InteractionReply> group = service.handle(groupMessage("qq-p1",
                "/确认 " + draft.draftId(), "m-group"));
        assertThat(group).isEmpty();
        assertThat(f.turn().decisions()).isEmpty();

        List<InteractionReply> otherPlayer = service.handle(privateMessage("qq-p2",
                "/确认 " + draft.draftId(), "m-other"));
        assertThat(otherPlayer).hasSize(1);
        assertThat(otherPlayer.get(0).text()).contains("不能确认他人的草稿");
        assertThat(f.turn().decisions()).isEmpty();

        List<InteractionReply> unmapped = service.handle(privateMessage("qq-unknown",
                "/确认 " + draft.draftId(), "m-unknown"));
        assertThat(unmapped).hasSize(1);
        assertThat(unmapped.get(0).text()).contains("未授权");
        assertThat(f.turn().decisions()).isEmpty();

        List<InteractionReply> owner = service.handle(privateMessage("qq-p1",
                "/确认 " + draft.draftId(), "m-owner"));
        assertThat(owner).hasSize(1);
        assertThat(owner.get(0).text()).contains("已确认决策");
        assertThat(f.turn().decisions()).hasSize(1);
        assertThat(f.turn().decisions().get(0).playerId()).isEqualTo("player-1");
        assertThat(f.turn().decisions().get(0).confirmedByPlayerId()).isEqualTo("player-1");
    }

    private static InboundMessage privateMessage(String qqId, String text, String dedupe) {
        return new InboundMessage("adapter-1", "bot-1", "msg-" + UUID.randomUUID(),
                qqId, InboundMessage.SessionType.PRIVATE, null, text,
                AgentTestFixture.BASE, dedupe);
    }

    private static InboundMessage groupMessage(String qqId, String text, String dedupe) {
        return new InboundMessage("adapter-1", "bot-1", "msg-" + UUID.randomUUID(),
                qqId, InboundMessage.SessionType.GROUP, "group-public", text,
                AgentTestFixture.BASE, dedupe);
    }
}
