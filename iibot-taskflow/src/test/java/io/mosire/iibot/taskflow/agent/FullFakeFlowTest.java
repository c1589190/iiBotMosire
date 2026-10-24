package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 全 Fake 纵向切片：注册/装载 → 开回合 → 秘书草稿 → 硬确认 → 冻结 → 推演 → 发布。 */
class FullFakeFlowTest {

    @Test
    void completeOfflineInteractionAndAdjudicationFlow() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("public-1", 1, "港口迷雾与失踪船队", Visibility.PUBLIC));

        ScriptedLlm secretaryLlm = ScriptedLlm.with(
                LlmResponse.toolCall("draft-call", DecisionDraftCreateTool.NAME, Map.of(
                        "intent", "调查失踪船队",
                        "actions", List.of("询问船工", "搜查仓库"),
                        "resources", Map.of("people", "3"),
                        "target", "北码头",
                        "contingency", "遇到危险立即撤回")),
                LlmResponse.text("草稿已生成，请在私聊确认。"));
        ScriptedLlm adjudicationLlm = ScriptedLlm.with(
                LlmResponse.toolCall("submit-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "船队失踪的线索指向北码头。",
                        "playerOutcomes", Map.of("player-1", "你找到了船队留下的航海日志。"),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "north-pier", "value", "investigated")),
                        "nextHooks", List.of("航海日志提到暴风前夜"))),
                LlmResponse.text("推演完成。"));

        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        registry.register(new SecretarySession("player-1", f.p1, f.engine, secretaryLlm,
                f.noopBriefing(), f.director, f.clock, Duration.ofMinutes(15),
                Duration.ofMinutes(15)));
        registry.register(new SecretarySession("player-2", f.p2, f.engine,
                ScriptedLlm.with(LlmResponse.text("unused")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15)));
        AdjudicationService adjudicationService = new AdjudicationService(f.engine,
                adjudicationLlm);
        InteractionTaskflowService service = new InteractionTaskflowService(f.engine,
                f.identities(), registry, adjudicationService, () -> f.loadedFacts,
                "group-public", f.clock, 240, f.director);

        List<InteractionReply> opened = service.handle(privateMessage("qq-director",
                "/开回合 30", "m-open"));
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).text()).contains("回合已开启");

        List<InteractionReply> chat = service.handle(privateMessage("qq-p1",
                "帮我起草调查失踪船队的决策", "m-chat"));
        assertThat(chat).hasSize(1);
        DecisionDraft draft = f.turn().drafts().get(0);
        assertThat(draft.playerId()).isEqualTo("player-1");

        List<InteractionReply> confirmed = service.handle(privateMessage("qq-p1",
                "/确认 " + draft.draftId(), "m-confirm"));
        assertThat(confirmed.get(0).text()).contains("已确认");
        assertThat(f.turn().decisions()).hasSize(1);

        List<InteractionReply> locked = service.handle(privateMessage("qq-director",
                "/冻结", "m-lock"));
        assertThat(locked.get(0).text()).contains("回合已冻结");
        assertThat(f.turn().state()).isEqualTo(TurnState.LOCKED);

        List<InteractionReply> adjudicated = service.handle(privateMessage("qq-director",
                "/推演", "m-adjudicate"));
        assertThat(adjudicated.get(0).text()).contains("推演已提交");
        assertThat(f.turn().state()).isEqualTo(TurnState.COMMITTED);
        assertThat(f.engine.status().worldVersion()).isEqualTo(2);

        List<InteractionReply> published = service.handle(privateMessage("qq-director",
                "/发布", "m-publish"));
        assertThat(published.get(0).text()).contains("回合已发布");
        assertThat(f.turn().state()).isEqualTo(TurnState.PUBLISHED);
        assertThat(f.store.events()).extracting(event -> event.type())
                .contains(EventType.TURN_OPENED, EventType.DRAFT_CREATED,
                        EventType.DECISION_CONFIRMED, EventType.TURN_LOCKED,
                        EventType.ADJUDICATION_STARTED, EventType.ADJUDICATION_COMMITTED,
                        EventType.TURN_PUBLISHED);
    }

    private static InboundMessage privateMessage(String qqId, String text, String dedupe) {
        return new InboundMessage("adapter-1", "bot-1", "msg-" + UUID.randomUUID(),
                qqId, InboundMessage.SessionType.PRIVATE, null, text,
                AgentTestFixture.BASE, dedupe);
    }
}
