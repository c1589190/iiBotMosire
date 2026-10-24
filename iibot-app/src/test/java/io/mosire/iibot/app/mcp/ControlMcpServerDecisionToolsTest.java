package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.decision.LlmActionBatchExecutor;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.action.ActionPoolException;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolSnapshot;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.InMemoryActionPoolStore;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.agent.AdjudicationService;
import io.mosire.iibot.taskflow.agent.GmAideAgent;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.InMemoryComplaintStore;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.decision.ActionBatchExecutor;
import io.mosire.iibot.taskflow.decision.ActionPackageException;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageView;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 行动池/裁定包/批次的官方 MCP SDK loopback 集成测试；无 QQ、无真实网络。 */
@Timeout(60)
class ControlMcpServerDecisionToolsTest {

    private static final String PATH = "/decision/mcp";
    private static final String TOKEN = "test-decision-token-4f1c9d";
    private static final String TOKEN_ENV = "IIBOT_TEST_DECISION_MCP_TOKEN";
    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(BASE, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<McpSyncClient> clients = new ArrayList<>();
    private final Actor supervisor = new Actor("external-supervisor", ActorRole.SUPERVISOR);
    private final Actor director = new Actor("director-1", ActorRole.DIRECTOR);
    private final Actor p1 = new Actor("player-1", ActorRole.PLAYER);
    private final Actor p2 = new Actor("player-2", ActorRole.PLAYER);

    private ControlMcpServer server;
    private TaskflowEngine engine;
    private ActionPoolService actionPool;
    private ActionPackageService packageService;
    private ActionBatchExecutor batchExecutor;
    private FakeLlmClient llm;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        for (McpSyncClient client : clients) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                // 清理失败不影响判定
            }
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void fullAssemblyLists48ToolsAndActionPoolSummaryGetIgnoreStayScoped() throws Exception {
        startFullAssembly(FakeLlmClient.with(LlmResponse.text("不应调用")));
        McpSyncClient client = initializedClient();

        assertThat(client.listTools().tools().stream().map(McpSchema.Tool::name))
                .hasSize(48)
                .containsAll(GmActionPoolMcpTools.NAMES)
                .containsAll(GmDecisionPackageMcpTools.NAMES)
                .containsAll(GmActionBatchMcpTools.NAMES)
                .doesNotContain("tf_qq_send", "tf_confirm_decision", "shutdown")
                .doesNotContain(GmPublicationMcpTools.NAMES.toArray(String[]::new))
                .doesNotContain(GmRecordMcpTools.NAMES.toArray(String[]::new));

        PlayerAction a1 = actionPool.submit(p1, "行动池正文-仅 get 可见-a11",
                "private-chat", "decision-a1");
        PlayerAction a2 = actionPool.submit(p2, "行动池正文-待包选中-b22",
                "private-chat", "decision-a2");
        PlayerAction a3 = actionPool.submit(p1, "行动池正文-待忽略-c33",
                "private-chat", "decision-a3");

        McpSchema.CallToolResult list = client.callTool(new McpSchema.CallToolRequest(
                GmActionPoolMcpTools.LIST, Map.of("status", "QUEUED")));
        assertThat(list.isError()).as(wireText(list)).isFalse();
        JsonNode listData = data(list);
        assertThat(listData.path("count").asInt()).isEqualTo(3);
        assertThat(wireText(list))
                .contains(a1.actionId(), a2.actionId(), a3.actionId(), "QUEUED")
                .doesNotContain("行动池正文-仅 get 可见-a11",
                        "行动池正文-待包选中-b22", "行动池正文-待忽略-c33");

        McpSchema.CallToolResult get = client.callTool(new McpSchema.CallToolRequest(
                GmActionPoolMcpTools.GET, Map.of("actionId", a1.actionId())));
        assertThat(get.isError()).as(wireText(get)).isFalse();
        assertThat(wireText(get)).contains("行动池正文-仅 get 可见-a11");

        McpSchema.CallToolResult ignored = client.callTool(new McpSchema.CallToolRequest(
                GmActionPoolMcpTools.IGNORE, Map.of(
                        "actionId", a3.actionId(),
                        "idempotencyKey", "decision-ignore-a3",
                        "reason", "GM 选择忽略")));
        assertThat(ignored.isError()).as(wireText(ignored)).isFalse();
        assertThat(data(ignored).path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(wireText(ignored)).doesNotContain("行动池正文-待忽略-c33");
        McpSchema.CallToolResult replay = client.callTool(new McpSchema.CallToolRequest(
                GmActionPoolMcpTools.IGNORE, Map.of(
                        "actionId", a3.actionId(),
                        "idempotencyKey", "decision-ignore-a3",
                        "reason", "GM 选择忽略")));
        assertThat(replay.isError()).isFalse();
        assertThat(data(replay).path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(actionPool.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.WITHDRAWN);
    }

    @Test
    void createAppendFullReadDecideCommitExecutesOnlyApprovedAndReplayDoesNotCallLlmAgain()
            throws Exception {
        startFullAssembly(FakeLlmClient.with(
                LlmResponse.text("多行动推演：甲与丙完成，乙未执行。")));
        McpSyncClient client = initializedClient();

        PlayerAction a1 = actionPool.submit(p1, "同一玩家行动甲-已批-4a",
                "private-chat", "package-a1");
        PlayerAction a2 = actionPool.submit(p1, "同一玩家行动乙-未批-8b",
                "private-chat", "package-a2");
        PlayerAction a3 = actionPool.submit(p2, "另一玩家行动丙-已批-2c",
                "private-chat", "package-a3");

        McpSchema.CallToolResult created = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.CREATE, Map.of(
                        "title", "北码头双人行动",
                        "actionIds", List.of(a1.actionId(), a2.actionId()),
                        "deadlineSeconds", 3600,
                        "idempotencyKey", "package-create-1")));
        assertThat(created.isError()).as(wireText(created)).isFalse();
        String packageId = data(created).path("packageId").asText();
        assertThat(packageId).startsWith("pkg-");
        assertThat(wireText(created)).doesNotContain("同一玩家行动甲-已批-4a",
                "同一玩家行动乙-未批-8b");

        McpSchema.CallToolResult selectedIgnore = client.callTool(new McpSchema.CallToolRequest(
                GmActionPoolMcpTools.IGNORE, Map.of(
                        "actionId", a1.actionId(),
                        "idempotencyKey", "ignore-selected-a1",
                        "reason", "试图取消已选中行动")));
        assertThat(selectedIgnore.isError()).isTrue();
        assertThat(wireText(selectedIgnore))
                .contains(ActionPoolException.ACTION_IN_CANONICAL_PACKAGE)
                .doesNotContain("同一玩家行动甲-已批-4a");
        // canonical 占用优先于投影状态：投影本身仍是 SELECTED，且绝不能被 withdraw。
        assertThat(actionPool.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.SELECTED);

        McpSchema.CallToolResult appended = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.APPEND, Map.of(
                        "packageId", packageId,
                        "actionIds", List.of(a3.actionId()))));
        assertThat(appended.isError()).as(wireText(appended)).isFalse();
        assertThat(data(appended).path("itemCount").asInt()).isEqualTo(3);
        assertThat(data(appended).path("state").asText()).isEqualTo("PROPOSED");

        McpSchema.CallToolResult summary = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.GET, Map.of("packageId", packageId)));
        assertThat(summary.isError()).as(wireText(summary)).isFalse();
        assertThat(wireText(summary)).doesNotContain("\"args\"",
                "同一玩家行动甲-已批-4a", "同一玩家行动乙-未批-8b",
                "另一玩家行动丙-已批-2c");

        McpSchema.CallToolResult full = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.GET, Map.of("packageId", packageId, "full", true)));
        assertThat(full.isError()).as(wireText(full)).isFalse();
        assertThat(wireText(full)).contains("\"args\"", "同一玩家行动甲-已批-4a",
                "同一玩家行动乙-未批-8b", "另一玩家行动丙-已批-2c", "player-1", "player-2");

        McpSchema.CallToolResult decided = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.DECIDE, Map.of(
                        "packageId", packageId,
                        "approvedIndexes", List.of(0, 2),
                        "note", "只批甲与丙")));
        assertThat(decided.isError()).as(wireText(decided)).isFalse();
        assertThat(data(decided).path("state").asText()).isEqualTo("DECIDED");
        assertThat(wireText(decided)).doesNotContain("同一玩家行动甲-已批-4a");

        McpSchema.CallToolResult committed = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.COMMIT, Map.of("packageId", packageId)));
        assertThat(committed.isError()).as(wireText(committed)).isFalse();
        JsonNode commitData = data(committed);
        assertThat(commitData.path("receiptStatus").asText()).isEqualTo("COMMITTED");
        assertThat(commitData.path("actionCount").asInt()).isEqualTo(2);
        assertThat(commitData.path("resultSummary").asText())
                .contains("多行动推演：甲与丙完成");
        String batchId = commitData.path("batchId").asText();
        assertThat(batchId).startsWith("batch-");
        assertThat(wireText(committed)).doesNotContain("同一玩家行动乙-未批-8b");

        assertThat(actionPool.getForGm(a1.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        assertThat(actionPool.getForGm(a3.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.COMMITTED);
        PlayerAction unapproved = actionPool.getForGm(a2.actionId()).orElseThrow();
        assertThat(unapproved.status()).isEqualTo(ActionStatus.QUEUED);
        assertThat(unapproved.packageId()).isNull();

        assertThat(llm.requests()).hasSize(1);
        String prompt = allPromptText(llm.requests());
        assertThat(prompt).contains("同一玩家行动甲-已批-4a", "另一玩家行动丙-已批-2c")
                .doesNotContain("同一玩家行动乙-未批-8b");

        McpSchema.CallToolResult replay = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.COMMIT, Map.of("packageId", packageId)));
        assertThat(replay.isError()).as(wireText(replay)).isFalse();
        assertThat(data(replay).path("batchId").asText()).isEqualTo(batchId);
        assertThat(llm.requests()).hasSize(1);
        assertThat(packageService.batches()).hasSize(1);

        McpSchema.CallToolResult batchList = client.callTool(new McpSchema.CallToolRequest(
                GmActionBatchMcpTools.LIST, Map.of()));
        assertThat(batchList.isError()).as(wireText(batchList)).isFalse();
        assertThat(wireText(batchList)).contains(batchId, packageId,
                        "\"actionCount\":2", "\"status\":\"COMMITTED\"")
                .doesNotContain("同一玩家行动甲-已批-4a", "多行动推演：甲与丙完成");

        McpSchema.CallToolResult batchGet = client.callTool(new McpSchema.CallToolRequest(
                GmActionBatchMcpTools.GET, Map.of("batchId", batchId)));
        assertThat(batchGet.isError()).as(wireText(batchGet)).isFalse();
        assertThat(wireText(batchGet))
                .contains("同一玩家行动甲-已批-4a", "另一玩家行动丙-已批-2c",
                        "多行动推演：甲与丙完成")
                .doesNotContain("同一玩家行动乙-未批-8b");
    }

    @Test
    void firstItemDecisionFreezesEditsAndTurnOpenBoundaryStillAllowsMultipleBatches()
            throws Exception {
        startFullAssembly(FakeLlmClient.with(
                LlmResponse.text("第一批次结果：甲执行。"),
                LlmResponse.text("第二批次结果：乙与丙共同执行。")));
        McpSyncClient client = initializedClient();

        PlayerAction a1 = actionPool.submit(p1, "批次一行动甲-5d", "private-chat", "batch-a1");
        PlayerAction a2 = actionPool.submit(p1, "批次二行动乙-6e", "private-chat", "batch-a2");
        PlayerAction a3 = actionPool.submit(p2, "批次二行动丙-7f", "private-chat", "batch-a3");

        String package1 = data(callOk(client, GmDecisionPackageMcpTools.CREATE, Map.of(
                "title", "批次一",
                "actionIds", List.of(a1.actionId()),
                "deadlineSeconds", 3600,
                "idempotencyKey", "create-batch-1"))).path("packageId").asText();

        McpSchema.CallToolResult decidedItem = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.DECIDE_ITEM, Map.of(
                        "packageId", package1,
                        "index", 0,
                        "verdict", "approve",
                        "note", "批准第一批")));
        assertThat(decidedItem.isError()).as(wireText(decidedItem)).isFalse();
        assertThat(data(decidedItem).path("state").asText()).isEqualTo("DECIDED");

        McpSchema.CallToolResult frozen = client.callTool(new McpSchema.CallToolRequest(
                GmDecisionPackageMcpTools.APPEND, Map.of(
                        "packageId", package1,
                        "actionIds", List.of(a2.actionId()))));
        assertThat(frozen.isError()).isTrue();
        assertThat(wireText(frozen)).contains("PACKAGE_CONTENT_FROZEN");
        assertThat(actionPool.getForGm(a2.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);

        String batch1 = data(callOk(client, GmDecisionPackageMcpTools.COMMIT,
                Map.of("packageId", package1))).path("batchId").asText();

        // 旧回合状态机只作为一个边界存在；行动池/包 commit 不依赖或修改 OPEN 回合。
        assertThat(engine.openTurn(director, engine.status().engineRevision(),
                "open-turn-between-packages",
                BASE.plus(Duration.ofHours(2))).ok()).isTrue();
        TurnState openedState = engine.turnView().orElseThrow().state();
        assertThat(openedState).isEqualTo(TurnState.OPEN);

        String package2 = data(callOk(client, GmDecisionPackageMcpTools.CREATE, Map.of(
                "title", "批次二",
                "actionIds", List.of(a2.actionId(), a3.actionId()),
                "deadlineSeconds", 3600,
                "idempotencyKey", "create-batch-2"))).path("packageId").asText();
        callOk(client, GmDecisionPackageMcpTools.DECIDE, Map.of(
                "packageId", package2,
                "approvedIndexes", List.of(0, 1),
                "note", "批准第二批"));
        String batch2 = data(callOk(client, GmDecisionPackageMcpTools.COMMIT,
                Map.of("packageId", package2))).path("batchId").asText();

        assertThat(batch1).isNotEqualTo(batch2);
        assertThat(packageService.batches()).hasSize(2);
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.OPEN);
        assertThat(llm.requests()).hasSize(2);

        McpSchema.CallToolResult list = client.callTool(new McpSchema.CallToolRequest(
                GmActionBatchMcpTools.LIST, Map.of()));
        JsonNode batches = data(list).path("batches");
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).path("batchId").asText()).isEqualTo(batch1);
        assertThat(batches.get(1).path("batchId").asText()).isEqualTo(batch2);
        assertThat(wireText(list)).doesNotContain("批次一行动甲-5d", "第一批次结果：甲执行");
    }

    @Test
    void decisionToolsHaveSupervisorOnlyMetadataAndAuthorizerDeniesSecretary() {
        startServicesOnly();
        List<AgentTool> actionTools = GmActionPoolMcpTools.all(actionPool);
        List<AgentTool> packageTools = GmDecisionPackageMcpTools.all(
                packageService, batchExecutor, supervisor);
        List<AgentTool> batchTools = GmActionBatchMcpTools.all(packageService);
        List<AgentTool> all = new ArrayList<>();
        all.addAll(actionTools);
        all.addAll(packageTools);
        all.addAll(batchTools);

        assertThat(all).extracting(AgentTool::name)
                .containsExactlyElementsOf(concat(GmActionPoolMcpTools.NAMES,
                        GmDecisionPackageMcpTools.NAMES, GmActionBatchMcpTools.NAMES));
        assertThat(all).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly("supervisor")
                    .doesNotContain("*", "SYSTEM");
            assertThat(tool.spec().requiredLevel()).isNotEqualTo(AccessToken.SYSTEM);
            assertThat(tool.jsonSchema().toString()).doesNotContain("*", "SYSTEM");
        });
        assertThat(actionTools).allSatisfy(tool -> assertThat(
                tool.resources().namespaces()).containsExactly(ActionPoolStore.RESOURCE_NAMESPACE));
        assertThat(packageTools).allSatisfy(tool -> assertThat(
                tool.resources().namespaces())
                .containsExactly(GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE));
        assertThat(batchTools).allSatisfy(tool -> assertThat(
                tool.resources().namespaces())
                .containsExactly(GmDecisionToolSupport.ACTION_BATCH_NAMESPACE));
        assertThat(find(actionTools, GmActionPoolMcpTools.LIST).spec().destructive()).isFalse();
        assertThat(find(actionTools, GmActionPoolMcpTools.GET).spec().destructive()).isFalse();
        assertThat(find(actionTools, GmActionPoolMcpTools.IGNORE).spec().destructive()).isTrue();
        assertThat(find(packageTools, GmDecisionPackageMcpTools.LIST).spec().destructive())
                .isFalse();
        assertThat(find(packageTools, GmDecisionPackageMcpTools.GET).spec().destructive())
                .isFalse();
        for (String name : List.of(GmDecisionPackageMcpTools.CREATE,
                GmDecisionPackageMcpTools.APPEND, GmDecisionPackageMcpTools.REPLACE_ITEM,
                GmDecisionPackageMcpTools.REMOVE_ITEM, GmDecisionPackageMcpTools.DECIDE,
                GmDecisionPackageMcpTools.DECIDE_ITEM, GmDecisionPackageMcpTools.COMMIT)) {
            assertThat(find(packageTools, name).spec().destructive()).isTrue();
        }
        assertThat(find(batchTools, GmActionBatchMcpTools.LIST).spec().destructive()).isFalse();
        assertThat(find(batchTools, GmActionBatchMcpTools.GET).spec().destructive()).isFalse();

        ToolResult denied = callAsRole(all, "secretary", GmActionPoolMcpTools.LIST,
                Map.of(), allNames());
        assertThat(denied.code()).isEqualTo("PERMISSION_DENIED");
        ToolResult supervisorAllowed = callAsRole(all, "supervisor",
                GmActionPoolMcpTools.LIST, Map.of(), allNames());
        assertThat(supervisorAllowed.success()).isTrue();
    }

    private void startFullAssembly(FakeLlmClient fakeLlm) throws Exception {
        startServicesOnly(fakeLlm);
        SecretaryWorkspaceStore workspaceStore = SecretaryWorkspaceStore.open(
                tempDir.resolve("workspaces"), List.of("player-1", "player-2"));
        SecretarySessionRegistry sessions = new SecretarySessionRegistry();
        PostService postService = new PostService(new InMemoryPostStore(), CLOCK);
        SharedSkillStore sharedSkillStore = SharedSkillStore.open(
                tempDir.resolve("shared-skills"));
        ComplaintService complaintService = new ComplaintService(
                new InMemoryComplaintStore(), CLOCK);
        InMemoryEventReadPort eventReadPort = new InMemoryEventReadPort(
                new InMemoryEventStore());
        server = ControlMcpServer.builder(engine, eventReadPort, supervisor,
                        ControlMcpConfig.enabled("127.0.0.1", 0, PATH, TOKEN_ENV))
                .secretProvider(SecretProvider.of(Map.of(TOKEN_ENV, TOKEN)))
                .clock(CLOCK)
                .adjudicationService(new AdjudicationService(engine, fakeLlm))
                .gmAideAgent(new GmAideAgent(engine, fakeLlm, eventReadPort))
                .secretaryWorkspaceStore(workspaceStore)
                .secretarySessions(sessions)
                .postService(postService)
                .sharedSkillStore(sharedSkillStore)
                .complaintService(complaintService)
                .actionPoolService(actionPool)
                .actionPackageService(packageService)
                .actionBatchExecutor(batchExecutor)
                .build();
        server.start();
    }

    @Test
    void ignoreUsesCanonicalOwnerEvenWhenProjectionRemainsStaleQueued() {
        FlakyActionPoolStore flaky = new FlakyActionPoolStore();
        ActionPoolService pool = new ActionPoolService(flaky, CLOCK);
        ActionPackageService packages = new ActionPackageService(
                pool, new DecisionPackages(), CLOCK);

        PlayerAction proposedAction = pool.submit(p1, "stale proposed 正文",
                "chat", "stale-proposed-a1");
        flaky.failSaves = true;
        PackageView proposed = packages.create(supervisor, "stale proposed 包",
                List.of(proposedAction.actionId()), 3600, "stale-create");
        assertThat(pool.getForGm(proposedAction.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.QUEUED);
        assertIgnoreRejected(pool, packages, proposedAction.actionId(),
                "ignore-stale-proposed", ActionStatus.QUEUED);

        packages.decide(supervisor, proposed.packageId(), Set.of(0), "decide");
        assertIgnoreRejected(pool, packages, proposedAction.actionId(),
                "ignore-stale-decided", ActionStatus.QUEUED);
        CommitReceipt committed = packages.commit(supervisor, proposed.packageId(),
                batch -> batch.committed("stale commit"));
        assertThat(committed.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
        assertIgnoreRejected(pool, packages, proposedAction.actionId(),
                "ignore-stale-committed-approved", ActionStatus.QUEUED);

        flaky.failSaves = false;
        PlayerAction approved = pool.submit(p1, "stale approved 正文",
                "chat", "stale-approved");
        PlayerAction unapproved = pool.submit(p1, "stale unapproved 正文",
                "chat", "stale-unapproved");
        flaky.failSaves = true;
        PackageView subset = packages.create(supervisor, "stale subset 包",
                List.of(approved.actionId(), unapproved.actionId()), 3600,
                "stale-create-2");
        packages.decide(supervisor, subset.packageId(), Set.of(0), "只批一");
        packages.commit(supervisor, subset.packageId(),
                batch -> batch.committed("stale subset"));
        flaky.failSaves = false;
        packages.reconcileActionsFromPackages();
        assertIgnoreRejected(pool, packages, approved.actionId(),
                "ignore-stale-committed-approved-2", ActionStatus.COMMITTED);
        ToolResult unapprovedIgnore = ignore(pool, packages, unapproved.actionId(),
                "ignore-committed-unapproved");
        assertThat(unapprovedIgnore.success()).as(unapprovedIgnore.code()).isTrue();
        assertThat(pool.getForGm(unapproved.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.WITHDRAWN);

        PlayerAction terminalAction = pool.submit(p1, "terminal 正文",
                "chat", "stale-terminal");
        PackageView terminal = packages.create(supervisor, "terminal 包",
                List.of(terminalAction.actionId()), 3600, "stale-create-3");
        packages.decide(supervisor, terminal.packageId(), Set.of(0), "批准");
        CommitReceipt rejected = packages.commit(supervisor, terminal.packageId(),
                batch -> {
                    throw new IllegalStateException("executor boom");
                });
        assertThat(rejected.status()).isEqualTo(CommitReceipt.Status.REJECTED);
        ToolResult terminalIgnore = ignore(pool, packages, terminalAction.actionId(),
                "ignore-terminal");
        assertThat(terminalIgnore.success()).as(terminalIgnore.code()).isTrue();
        assertThat(pool.getForGm(terminalAction.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.WITHDRAWN);
    }

    @Test
    void concurrentCreateAndIgnoreCannotCreateCanonicalWithdrawnAction() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                FlakyActionPoolStore flaky = new FlakyActionPoolStore();
                ActionPoolService pool = new ActionPoolService(flaky, CLOCK);
                ActionPackageService packages = new ActionPackageService(
                        pool, new DecisionPackages(), CLOCK);
                int roundNo = round;
                PlayerAction action = pool.submit(p1, "race 行动-" + roundNo,
                        "chat", "race-submit-" + roundNo);
                String actionId = action.actionId();
                // create 的 SELECTED 投影写失败 => canonical owner 存在但 ActionPool 仍 stale QUEUED。
                flaky.failProjectionSaves = true;

                CountDownLatch start = new CountDownLatch(1);
                Future<String> create = executor.submit(() -> {
                    start.await();
                    try {
                        packages.create(supervisor, "race 包-" + roundNo,
                                List.of(actionId), 3600, "race-create-" + roundNo);
                        return "OK";
                    } catch (ActionPackageException failure) {
                        return failure.code();
                    }
                });
                Future<ToolResult> ignore = executor.submit(() -> {
                    start.await();
                    return ignore(pool, packages, actionId, "race-ignore-" + roundNo);
                });
                start.countDown();
                String createCode = create.get(10, TimeUnit.SECONDS);
                ToolResult ignoreResult = ignore.get(10, TimeUnit.SECONDS);

                flaky.failProjectionSaves = false;
                boolean createWon = "OK".equals(createCode);
                if (createWon) {
                    assertThat(ignoreResult.success()).isFalse();
                    assertThat(ignoreResult.code())
                            .isEqualTo(ActionPoolException.ACTION_IN_CANONICAL_PACKAGE);
                    assertThat(pool.getForGm(action.actionId()).orElseThrow().status())
                            .isEqualTo(ActionStatus.QUEUED);
                    assertThat(packages.list()).hasSize(1);
                    packages.reconcileActionsFromPackages();
                    assertThat(pool.getForGm(action.actionId()).orElseThrow().status())
                            .isEqualTo(ActionStatus.SELECTED);
                } else {
                    assertThat(ignoreResult.success()).isTrue();
                    assertThat(pool.getForGm(action.actionId()).orElseThrow().status())
                            .isEqualTo(ActionStatus.WITHDRAWN);
                    assertThat(packages.list()).isEmpty();
                    assertThat(createCode).isIn(ActionPackageException.CONFLICT,
                            ActionPackageException.INVALID_STATUS);
                    packages.reconcileActionsFromPackages();
                }
                // canonical package + WITHDRAWN 不可能同时存在；reconcile 不得失败。
                assertThat(packages.actionReconcilePending()).isFalse();
            }
        } finally {
            executor.shutdownNow();
        }

        // 确定性 ignore-first 路径：action 先 WITHDRAWN，create 必须失败且零 canonical 包。
        FlakyActionPoolStore sequentialFlaky = new FlakyActionPoolStore();
        ActionPoolService sequentialPool = new ActionPoolService(sequentialFlaky, CLOCK);
        ActionPackageService sequentialPackages = new ActionPackageService(
                sequentialPool, new DecisionPackages(), CLOCK);
        PlayerAction sequentialAction = sequentialPool.submit(p1, "ignore-first 行动",
                "chat", "ignore-first-submit");
        ToolResult ignoreFirst = ignore(sequentialPool, sequentialPackages,
                sequentialAction.actionId(), "ignore-first");
        assertThat(ignoreFirst.success()).as(ignoreFirst.code()).isTrue();
        assertThat(sequentialPool.getForGm(sequentialAction.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.WITHDRAWN);
        assertThatThrownBy(() -> sequentialPackages.create(supervisor, "ignore-first 包",
                List.of(sequentialAction.actionId()), 3600, "ignore-first-create"))
                .isInstanceOfSatisfying(ActionPackageException.class,
                        failure -> assertThat(failure.code()).isIn(
                                ActionPackageException.CONFLICT,
                                ActionPackageException.INVALID_STATUS));
        assertThat(sequentialPackages.list()).isEmpty();
    }

    private static void assertIgnoreRejected(ActionPoolService pool,
                                             ActionPackageService packages,
                                             String actionId, String key,
                                             ActionStatus expectedStatus) {
        ToolResult result = ignore(pool, packages, actionId, key);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(ActionPoolException.ACTION_IN_CANONICAL_PACKAGE);
        assertThat(pool.getForGm(actionId).orElseThrow().status())
                .isEqualTo(expectedStatus);
    }

    private static ToolResult ignore(ActionPoolService pool, ActionPackageService packages,
                                     String actionId, String key) {
        return callAsRole(List.of(GmActionPoolMcpTools.ignore(pool, packages)),
                "supervisor", GmActionPoolMcpTools.IGNORE, Map.of(
                        "actionId", actionId,
                        "idempotencyKey", key,
                        "reason", "canonical owner 测试"),
                Set.of(GmActionPoolMcpTools.IGNORE));
    }

    private void startServicesOnly() {
        startServicesOnly(FakeLlmClient.with());
    }

    private void startServicesOnly(FakeLlmClient fakeLlm) {
        try {
            llm = fakeLlm;
            engine = new TaskflowEngine(CLOCK, new InMemoryEventStore());
            assertThat(engine.registerDirector(director,
                    new PlayerProfile("director-1", "qq-director", "Director"),
                    engine.status().engineRevision(), "register-director").ok()).isTrue();
            assertThat(engine.registerPlayer(director,
                    new PlayerProfile("player-1", "qq-p1", "Player One"),
                    engine.status().engineRevision(), "register-p1").ok()).isTrue();
            assertThat(engine.registerPlayer(director,
                    new PlayerProfile("player-2", "qq-p2", "Player Two"),
                    engine.status().engineRevision(), "register-p2").ok()).isTrue();
            actionPool = new ActionPoolService(new InMemoryActionPoolStore(), CLOCK);
            packageService = new ActionPackageService(actionPool,
                    new DecisionPackages(), CLOCK);
            batchExecutor = new LlmActionBatchExecutor(engine, fakeLlm, CLOCK);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private McpSyncClient initializedClient() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.boundPort())
                .endpoint(PATH)
                .resumableStreams(false)
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        clients.add(client);
        client.initialize();
        return client;
    }

    private static McpSchema.CallToolResult callOk(McpSyncClient client, String tool,
                                                   Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("工具 %s 应成功: %s", tool, wireText(result)).isFalse();
        return result;
    }

    private static JsonNode data(McpSchema.CallToolResult result) throws Exception {
        return JSON.readTree(wireText(result)).path("data");
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static String allPromptText(List<io.mosire.agentlib.llm.LlmRequest> requests) {
        StringBuilder result = new StringBuilder();
        for (var request : requests) {
            for (var message : request.messages()) {
                for (var part : message.content()) {
                    if (part instanceof io.mosire.agentlib.llm.ContentPart.Text text) {
                        result.append(text.text()).append('\n');
                    }
                }
            }
        }
        return result.toString();
    }

    private static List<String> concat(List<String>... lists) {
        List<String> values = new ArrayList<>();
        for (List<String> list : lists) {
            values.addAll(list);
        }
        return values;
    }

    private static Set<String> allNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(GmActionPoolMcpTools.NAMES);
        names.addAll(GmDecisionPackageMcpTools.NAMES);
        names.addAll(GmActionBatchMcpTools.NAMES);
        return names;
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static ToolResult callAsRole(List<AgentTool> tools, String role,
                                         String toolName, Map<String, Object> arguments,
                                         Set<String> allowedNames) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        Map<String, ResourceScope> scopes = new java.util.LinkedHashMap<>();
        scopes.put(ActionPoolStore.RESOURCE_NAMESPACE,
                ResourceScope.of(ActionPoolStore.RESOURCE_NAMESPACE));
        scopes.put(GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE,
                ResourceScope.of(GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE));
        scopes.put(GmDecisionToolSupport.ACTION_BATCH_NAMESPACE,
                ResourceScope.of(GmDecisionToolSupport.ACTION_BATCH_NAMESPACE));
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allowedNames.toArray(String[]::new))
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(scopes))
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), arguments, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static final class FlakyActionPoolStore implements ActionPoolStore {
        private final InMemoryActionPoolStore delegate = new InMemoryActionPoolStore();
        private boolean failSaves;
        private boolean failProjectionSaves;

        @Override
        public ActionPoolSnapshot load() {
            return delegate.load();
        }

        @Override
        public void save(ActionPoolSnapshot snapshot) {
            if (failSaves) {
                throw new IllegalStateException("模拟 ActionPool 投影写失败");
            }
            delegate.save(snapshot);
        }

        @Override
        public void save(ActionPoolSnapshot snapshot, DomainEvent event) {
            if (failSaves || (failProjectionSaves && event != null
                    && isProjectionEvent(event.eventType()))) {
                throw new IllegalStateException("模拟 ActionPool 投影写失败");
            }
            delegate.save(snapshot);
        }

        private static boolean isProjectionEvent(String eventType) {
            return DomainEventTypes.ACTION_SELECTED.equals(eventType)
                    || DomainEventTypes.ACTION_RELEASED.equals(eventType)
                    || DomainEventTypes.ACTION_COMMITTED.equals(eventType);
        }
    }
}
