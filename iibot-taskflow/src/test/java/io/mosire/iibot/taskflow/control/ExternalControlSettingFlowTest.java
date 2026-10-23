package io.mosire.iibot.taskflow.control;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.SettingProposal;
import io.mosire.iibot.taskflow.SettingProposalStatus;
import io.mosire.iibot.taskflow.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 外部 supervisor 通过控制工具完成设定提案、读取与审核。 */
class ExternalControlSettingFlowTest {

    @Test
    void supervisorCanProposeListApproveAndRespectVisibility() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult proposed = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                ControlTestFixture.args(
                        "content", "海港在双月之夜会浮现古代灯塔",
                        "visibility", "PUBLIC",
                        "expectedRevision", fixture.status().engineRevision(),
                        "idempotencyKey", "setting-propose-public",
                        "reason", "外部控制面提交公开设定"));
        assertThat(proposed.code()).isNull();
        JsonNode proposedJson = fixture.json(proposed);
        String proposalId = proposedJson.path("data").path("details")
                .path("proposalId").asText();
        assertThat(proposalId).isNotBlank();
        assertThat(proposedJson.path("data").path("details").path("status").asText())
                .isEqualTo("PENDING");
        assertThat(proposedJson.path("data").path("referenceId").asText())
                .isEqualTo(proposalId);
        assertThat(fixture.status().worldVersion()).isZero();
        assertThat(fixture.engine.visibleFactsFor("player-1")).isEmpty();

        ToolResult list = fixture.call(control, ExternalControlToolSet.TF_SETTING_LIST, Map.of());
        assertThat(list.code()).isNull();
        JsonNode listJson = fixture.json(list).path("data");
        assertThat(listJson.path("count").asInt()).isEqualTo(1);
        assertThat(listJson.path("proposals").get(0).path("proposalId").asText())
                .isEqualTo(proposalId);
        assertThat(listJson.path("proposals").get(0).path("authorActorId").asText())
                .isEqualTo("external-supervisor");
        assertThat(listJson.path("proposals").get(0).path("status").asText())
                .isEqualTo("PENDING");

        long approveRevision = fixture.status().engineRevision();
        ToolResult approved = fixture.call(control, ExternalControlToolSet.TF_SETTING_DECIDE,
                ControlTestFixture.args(
                        "proposalId", proposalId,
                        "approve", true,
                        "note", "符合公开世界观",
                        "expectedRevision", approveRevision,
                        "idempotencyKey", "setting-approve-public",
                        "reason", "外部控制面批准设定"));
        assertThat(approved.code()).isNull();
        JsonNode approvedJson = fixture.json(approved).path("data");
        assertThat(approvedJson.path("details").path("status").asText())
                .isEqualTo("APPROVED");
        assertThat(approvedJson.path("details").path("worldVersion").asText())
                .isEqualTo("1");
        assertThat(approvedJson.path("details").path("worldFactId").asText())
                .isEqualTo("setting-" + proposalId);
        assertThat(fixture.status().worldVersion()).isEqualTo(1);
        assertThat(fixture.engine.visibleFactsFor("player-1"))
                .anySatisfy(fact -> {
                    assertThat(fact.factId()).isEqualTo("setting-" + proposalId);
                    assertThat(fact.content()).contains("古代灯塔");
                    assertThat(fact.visibility()).isEqualTo(io.mosire.iibot.taskflow.Visibility.PUBLIC);
                });

        ToolResult proposalById = fixture.call(control, ExternalControlToolSet.TF_SETTING_LIST,
                Map.of("proposalId", proposalId));
        assertThat(proposalById.code()).isNull();
        JsonNode byId = fixture.json(proposalById).path("data").path("proposal");
        assertThat(byId.path("status").asText()).isEqualTo("APPROVED");
        assertThat(byId.path("worldFactId").asText()).isEqualTo("setting-" + proposalId);
        assertThat(byId.path("contentLength").asInt()).isEqualTo("海港在双月之夜会浮现古代灯塔".length());

        ToolResult playersProposal = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                ControlTestFixture.args(
                        "content", "只有玩家一知道的暗号",
                        "visibility", Map.of("type", "PLAYERS", "playerIds", List.of("player-1")),
                        "expectedRevision", fixture.status().engineRevision(),
                        "idempotencyKey", "setting-propose-players",
                        "reason", "外部控制面提交玩家可见设定"));
        assertThat(playersProposal.code()).isNull();
        String playersProposalId = fixture.json(playersProposal).path("data")
                .path("details").path("proposalId").asText();
        ToolResult playersApproved = fixture.call(control, ExternalControlToolSet.TF_SETTING_DECIDE,
                ControlTestFixture.args(
                        "proposalId", playersProposalId,
                        "approve", true,
                        "expectedRevision", fixture.status().engineRevision(),
                        "idempotencyKey", "setting-approve-players",
                        "reason", "外部控制面批准定向设定"));
        assertThat(playersApproved.code()).isNull();
        assertThat(fixture.status().worldVersion()).isEqualTo(2);
        assertThat(fixture.engine.visibleFactsFor("player-1"))
                .anyMatch(fact -> fact.factId().equals("setting-" + playersProposalId));
        assertThat(fixture.engine.visibleFactsFor("player-2"))
                .noneMatch(fact -> fact.factId().equals("setting-" + playersProposalId));
    }

    @Test
    void supervisorCanRejectWithoutChangingWorldVersionOrFacts() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult proposed = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                ControlTestFixture.args(
                        "content", "不会被批准的禁忌航道",
                        "visibility", "PUBLIC",
                        "expectedRevision", 0L,
                        "idempotencyKey", "reject-propose",
                        "reason", "提交拒绝测试"));
        String proposalId = fixture.json(proposed).path("data")
                .path("details").path("proposalId").asText();
        long worldVersionBefore = fixture.status().worldVersion();

        ToolResult rejected = fixture.call(control, ExternalControlToolSet.TF_SETTING_DECIDE,
                ControlTestFixture.args(
                        "proposalId", proposalId,
                        "approve", false,
                        "note", "与既有设定冲突",
                        "expectedRevision", fixture.status().engineRevision(),
                        "idempotencyKey", "reject-decide",
                        "reason", "拒绝设定"));
        assertThat(rejected.code()).isNull();
        JsonNode rejectedJson = fixture.json(rejected).path("data");
        assertThat(rejectedJson.path("details").path("status").asText())
                .isEqualTo("REJECTED");
        assertThat(rejectedJson.path("details").path("worldVersion").asText())
                .isEqualTo(Long.toString(worldVersionBefore));
        assertThat(fixture.status().worldVersion()).isEqualTo(worldVersionBefore);
        SettingProposal proposal = fixture.engine.settingProposal(proposalId).orElseThrow();
        assertThat(proposal.status()).isEqualTo(SettingProposalStatus.REJECTED);
        assertThat(proposal.worldFactId()).isNull();
        assertThat(proposal.reviewNote()).isEqualTo("与既有设定冲突");
        assertThat(fixture.engine.visibleFactsFor("player-1"))
                .noneMatch(fact -> fact.content().contains("禁忌航道"));
        assertThat(fixture.store.events().get(fixture.store.size() - 1).type())
                .isEqualTo(EventType.SETTING_REJECTED);
    }

    @Test
    void permissionScopeRevisionUnknownAndDuplicateKeysHaveZeroSideEffects() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        Map<String, Object> proposeArgs = ControlTestFixture.args(
                "content", "审计边界设定",
                "visibility", "PUBLIC",
                "expectedRevision", 0L,
                "idempotencyKey", "setting-boundary",
                "reason", "边界测试");

        ToolResult guest = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                proposeArgs, AccessToken.GUEST, RoleSet.of(ControlTestFixture.SUPERVISOR_ROLE),
                ResourceScopeMap.of("taskflow", ResourceScope.unlimited()));
        assertThat(guest.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(fixture.store.size()).isZero();
        assertThat(fixture.status().engineRevision()).isZero();

        ToolResult wrongScope = fixture.callRaw(control,
                ExternalControlToolSet.TF_SETTING_PROPOSE, proposeArgs, ResourceScopeMap.empty());
        assertThat(wrongScope.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(fixture.store.size()).isZero();

        ToolResult wrongRevision = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                ControlTestFixture.args(
                        "content", "错误 revision 设定",
                        "visibility", "PUBLIC",
                        "expectedRevision", 99L,
                        "idempotencyKey", "setting-wrong-revision",
                        "reason", "错误 revision"));
        assertThat(wrongRevision.code()).isEqualTo("REVISION_CONFLICT");
        assertThat(fixture.store.size()).isZero();
        assertThat(fixture.status().worldVersion()).isZero();

        ToolResult first = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                proposeArgs);
        ToolResult duplicate = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                ControlTestFixture.args(
                        "content", "重复 key 不应生效的不同内容",
                        "visibility", "DIRECTOR_ONLY",
                        "expectedRevision", fixture.status().engineRevision(),
                        "idempotencyKey", "setting-boundary",
                        "reason", "重复 key"));
        assertThat(first.code()).isNull();
        assertThat(duplicate.code()).isNull();
        assertThat(duplicate.message()).isEqualTo(first.message());
        assertThat(fixture.store.size()).isEqualTo(1);
        assertThat(fixture.status().engineRevision()).isEqualTo(1);
        assertThat(fixture.engine.pendingSettings()).hasSize(1);

        int eventsBeforeUnknown = fixture.store.size();
        long revisionBeforeUnknown = fixture.status().engineRevision();
        ToolResult unknown = fixture.call(control, ExternalControlToolSet.TF_SETTING_DECIDE,
                ControlTestFixture.args(
                        "proposalId", "missing-proposal",
                        "approve", true,
                        "expectedRevision", revisionBeforeUnknown,
                        "idempotencyKey", "setting-unknown",
                        "reason", "未知提案"));
        assertThat(unknown.code()).isEqualTo("NOT_FOUND");
        assertThat(fixture.store.size()).isEqualTo(eventsBeforeUnknown);
        assertThat(fixture.status().engineRevision()).isEqualTo(revisionBeforeUnknown);
        assertThat(fixture.status().worldVersion()).isZero();

        ToolResult unknownList = fixture.call(control, ExternalControlToolSet.TF_SETTING_LIST,
                Map.of("proposalId", "missing-proposal"));
        assertThat(unknownList.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void settingListOnlyReturnsBoundedContentPreview() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        String marker = "LONG_SETTING_SECRET_MARKER";
        String content = "A".repeat(200) + marker + "B".repeat(2_000);

        ToolResult proposed = fixture.call(control, ExternalControlToolSet.TF_SETTING_PROPOSE,
                ControlTestFixture.args(
                        "content", content,
                        "visibility", "PUBLIC",
                        "expectedRevision", 0L,
                        "idempotencyKey", "long-setting-propose",
                        "reason", "长正文摘要测试"));
        assertThat(proposed.code()).isNull();
        String proposalId = fixture.json(proposed).path("data")
                .path("details").path("proposalId").asText();

        ToolResult pending = fixture.call(control, ExternalControlToolSet.TF_SETTING_LIST, Map.of());
        ToolResult byId = fixture.call(control, ExternalControlToolSet.TF_SETTING_LIST,
                Map.of("proposalId", proposalId));
        assertThat(pending.code()).isNull();
        assertThat(byId.code()).isNull();
        assertThat(pending.message()).doesNotContain(marker);
        assertThat(byId.message()).doesNotContain(marker);

        JsonNode pendingItem = fixture.json(pending).path("data").path("proposals").get(0);
        JsonNode byIdItem = fixture.json(byId).path("data").path("proposal");
        assertThat(pendingItem.path("contentLength").asInt()).isEqualTo(content.length());
        assertThat(pendingItem.path("contentPreview").asText()).hasSizeLessThanOrEqualTo(160);
        assertThat(pendingItem.path("contentPreviewTruncated").asBoolean()).isTrue();
        assertThat(byIdItem.path("contentPreview").asText()).hasSizeLessThanOrEqualTo(160);
        assertThat(byIdItem.path("contentPreviewTruncated").asBoolean()).isTrue();
    }
}
