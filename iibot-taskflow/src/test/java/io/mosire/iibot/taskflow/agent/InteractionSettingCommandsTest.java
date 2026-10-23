package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.SettingProposal;
import io.mosire.iibot.taskflow.SettingProposalStatus;
import io.mosire.iibot.taskflow.Visibility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 私聊设定命令必须优先于秘书 LLM，并端到端走提案/审核领域流程。 */
class InteractionSettingCommandsTest {

    @Test
    void settingCommandsWorkEndToEndWithoutCallingSecretaryLlm() {
        AgentTestFixture f = AgentTestFixture.create();
        ScriptedLlm secretaryLlm = ScriptedLlm.with(
                LlmResponse.text("这条回复不应出现：命令被误交给秘书。"));
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        registry.register(new SecretarySession("player-1", f.p1, f.engine, secretaryLlm,
                f.noopBriefing(), f.director, f.clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15)));
        InteractionTaskflowService service = new InteractionTaskflowService(f.engine,
                f.identities(), registry,
                new AdjudicationService(f.engine, ScriptedLlm.with(LlmResponse.text("unused"))),
                () -> f.loadedFacts, "group-public", f.clock);

        long worldVersionBefore = f.engine.status().worldVersion();
        List<InteractionReply> proposed = service.handle(privateMessage("qq-p1",
                "/提议设定 海港的潮汐会随双月同步", "m-propose"));
        assertThat(proposed).hasSize(1);
        assertThat(proposed.get(0).text()).contains("等待推演审核");
        String proposalId = lineValue(proposed.get(0).text(), "proposalId: ");
        SettingProposal pending = f.engine.settingProposal(proposalId).orElseThrow();
        assertThat(pending.status()).isEqualTo(SettingProposalStatus.PENDING);
        assertThat(pending.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(pending.authorActorId()).isEqualTo("player-1");
        assertThat(secretaryLlm.requests()).isEmpty();

        List<InteractionReply> pendingList = service.handle(privateMessage("qq-director",
                "/待审设定", "m-pending"));
        assertThat(pendingList).hasSize(1);
        assertThat(pendingList.get(0).text())
                .contains("待审设定")
                .contains(proposalId)
                .contains("author=player-1")
                .contains("visibility=PUBLIC");

        List<InteractionReply> approved = service.handle(privateMessage("qq-director",
                "/通过设定 " + proposalId, "m-approve"));
        assertThat(approved).hasSize(1);
        assertThat(approved.get(0).text())
                .contains("已通过设定")
                .contains("worldFactId: setting-" + proposalId)
                .contains("worldVersion: " + (worldVersionBefore + 1));
        assertThat(f.engine.status().worldVersion()).isEqualTo(worldVersionBefore + 1);
        assertThat(f.engine.visibleFactsFor("player-1"))
                .anyMatch(fact -> fact.factId().equals("setting-" + proposalId)
                        && fact.content().contains("双月同步"));

        long worldVersionAfterApprove = f.engine.status().worldVersion();
        List<InteractionReply> rejectProposal = service.handle(privateMessage("qq-p1",
                "/提议设定 不应生效的禁忌航道", "m-propose-reject"));
        String rejectedId = lineValue(rejectProposal.get(0).text(), "proposalId: ");
        List<InteractionReply> rejected = service.handle(privateMessage("qq-director",
                "/拒绝设定 " + rejectedId + " 与已有航道冲突", "m-reject"));
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).text())
                .contains("已拒绝设定")
                .contains("status: REJECTED")
                .contains("（未改变）");
        assertThat(f.engine.status().worldVersion()).isEqualTo(worldVersionAfterApprove);
        assertThat(f.engine.settingProposal(rejectedId).orElseThrow().status())
                .isEqualTo(SettingProposalStatus.REJECTED);
        assertThat(f.engine.visibleFactsFor("player-1"))
                .noneMatch(fact -> fact.content().contains("禁忌航道"));

        List<InteractionReply> direct = service.handle(privateMessage("qq-director",
                "/设定 推演宣告北境进入长冬", "m-direct"));
        assertThat(direct).hasSize(1);
        assertThat(direct.get(0).text())
                .contains("设定已生效")
                .contains("worldVersion: " + (worldVersionAfterApprove + 1));
        String directProposalId = lineValue(direct.get(0).text(), "proposalId: ");
        assertThat(f.engine.settingProposal(directProposalId).orElseThrow().status())
                .isEqualTo(SettingProposalStatus.APPROVED);
        assertThat(f.engine.visibleFactsFor("player-1"))
                .anyMatch(fact -> fact.factId().equals("setting-" + directProposalId)
                        && fact.content().contains("北境进入长冬"));

        List<InteractionReply> playerCannotDirect = service.handle(privateMessage("qq-p1",
                "/设定 玩家试图直接改世界", "m-player-direct"));
        assertThat(playerCannotDirect.get(0).text()).contains("权限不足");
        assertThat(f.engine.status().worldVersion()).isEqualTo(worldVersionAfterApprove + 1);
        assertThat(secretaryLlm.requests()).isEmpty();
    }

    @Test
    void formatPermissionsAndUnknownProposalGiveActionableChineseErrors() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        registry.register(new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("unused")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15)));
        InteractionTaskflowService service = new InteractionTaskflowService(f.engine,
                f.identities(), registry,
                new AdjudicationService(f.engine, ScriptedLlm.with(LlmResponse.text("unused"))),
                () -> f.loadedFacts, "group-public", f.clock);

        assertThat(service.handle(privateMessage("qq-p1", "/提议设定", "m-empty-propose"))
                .get(0).text()).contains("用法");
        assertThat(service.handle(privateMessage("qq-director", "/设定", "m-empty-direct"))
                .get(0).text()).contains("用法");
        assertThat(service.handle(privateMessage("qq-p1", "/待审设定", "m-player-pending"))
                .get(0).text()).contains("权限不足");
        assertThat(service.handle(privateMessage("qq-p1",
                "/通过设定 missing-id", "m-player-approve")).get(0).text())
                .contains("权限不足");
        assertThat(service.handle(privateMessage("qq-director",
                "/通过设定 missing-id", "m-unknown-approve")).get(0).text())
                .contains("审核失败")
                .contains("NOT_FOUND");
        assertThat(service.handle(privateMessage("qq-director",
                "/通过设定 bad/id", "m-bad-id")).get(0).text())
                .contains("格式不合法");
    }

    private static InboundMessage privateMessage(String qqId, String text, String dedupe) {
        return new InboundMessage("adapter-1", "bot-1", "msg-" + UUID.randomUUID(),
                qqId, InboundMessage.SessionType.PRIVATE, null, text,
                AgentTestFixture.BASE, dedupe);
    }

    private static String lineValue(String text, String prefix) {
        return Arrays.stream(text.split("\\n"))
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("回复缺少 " + prefix + ":\n" + text));
    }
}
