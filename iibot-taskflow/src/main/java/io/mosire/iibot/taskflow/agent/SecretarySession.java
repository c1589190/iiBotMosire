package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.InfoBrief;
import io.mosire.iibot.taskflow.SecretaryKnowledge;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 每玩家一个的私聊秘书会话。
 *
 * <p>会话持有可复用 {@link ContextWindow} 与工具调用审计。system prompt 每次请求从
 * {@link TaskflowEngine#knowledgeForSecretary(String)} 重新组装，只包含公共事实与该玩家未过期
 * brief；绝不会把完整世界注入。运行期间不发送 QQ 消息，最终文本交给上层 outbox。</p>
 *
 * <p><b>线程安全</b>：同一窗口上的 {@link #chat(String)} 与 {@link #requestBrief(String)}
 * 通过本实例的监视器串行化；调用方不得在外部直接改写 {@link #contextWindow()}。</p>
 */
public final class SecretarySession {

    private static final String SECRETARY_ROLE = "secretary";

    private final String playerId;
    private final Actor player;
    private final TaskflowEngine engine;
    private final BriefingService briefingService;
    private final Actor briefIssuer;
    private final Clock clock;
    private final Duration draftTtl;
    private final Duration briefTtl;
    private final AgentRunner runner;
    private final ContextWindow window;
    private final List<AgentTool> tools;
    private final AgentPermissionSet permissions;
    private final SecretaryWorkspaceStore workspaceStore;
    private final PostService postService;
    private final List<ToolInvocation> toolInvocations = new ArrayList<>();

    /** 使用 15 分钟草稿/brief 有效期的便捷装配。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null);
    }

    /** 可注入秘书工作区商店的便捷装配。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, null);
    }

    /** 可注入推文服务；未注入时保持 v2-C 之前的秘书工具面。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, PostService postService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, postService);
    }

    /** 保留旧的显式有效期装配（无工作区/推文工具，行为与旧版本一致）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, null, null);
    }

    /** 可注入秘书工作区商店（store 置于有效期参数之前的便于阅读形态）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, SecretaryWorkspaceStore workspaceStore,
                            Duration draftTtl, Duration briefTtl) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, workspaceStore, null);
    }

    /** 可注入秘书工作区与推文服务，并显式指定草稿/brief 有效期。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, workspaceStore, null);
    }

    /** 同时注入工作区与推文服务的便捷装配。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService);
    }

    /** 工作区与推文服务同时注入，store 置于有效期参数之前。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, SecretaryWorkspaceStore workspaceStore,
                            Duration draftTtl, Duration briefTtl, PostService postService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, workspaceStore, postService);
    }

    /** 工作区与推文服务同时注入，store 置于有效期参数之后。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        this.playerId = playerId;
        this.player = Objects.requireNonNull(player, "player");
        if (player.role() != ActorRole.PLAYER || !player.actorId().equals(playerId)) {
            throw new IllegalArgumentException("秘书会话的 player 必须是同 ID 玩家");
        }
        this.engine = Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(llm, "llm");
        this.briefingService = Objects.requireNonNull(briefingService, "briefingService");
        this.briefIssuer = Objects.requireNonNull(briefIssuer, "briefIssuer");
        if (briefIssuer.role() != ActorRole.GM && briefIssuer.role() != ActorRole.DIRECTOR) {
            throw new IllegalArgumentException("briefIssuer 必须是 GM/DIRECTOR");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        if (draftTtl == null || draftTtl.isNegative() || draftTtl.isZero()) {
            throw new IllegalArgumentException("draftTtl 必须为正");
        }
        if (briefTtl == null || briefTtl.isNegative() || briefTtl.isZero()) {
            throw new IllegalArgumentException("briefTtl 必须为正");
        }
        this.draftTtl = draftTtl;
        this.briefTtl = briefTtl;
        this.workspaceStore = workspaceStore;
        this.postService = postService;
        this.window = new ContextWindow("secretary:" + playerId);

        List<AgentTool> availableTools = new ArrayList<>();
        availableTools.add(new DecisionDraftCreateTool(engine, clock));
        availableTools.add(new DecisionDraftUpdateTool(engine));
        availableTools.add(new RequestBriefTool(this::requestBrief));
        if (workspaceStore != null) {
            availableTools.addAll(SecretaryWorkspaceTools.all(workspaceStore));
        }
        if (postService != null) {
            availableTools.addAll(SecretaryPostTools.all(postService));
        }
        this.tools = List.copyOf(availableTools);

        AgentPermissionSet.Builder permissionBuilder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(DecisionDraftCreateTool.NAME, DecisionDraftUpdateTool.NAME,
                        RequestBriefTool.NAME)
                .roles(RoleSet.of(SECRETARY_ROLE));
        Map<String, ResourceScope> resourceScopes = new LinkedHashMap<>();
        if (workspaceStore != null) {
            permissionBuilder.allow(SecretaryWorkspaceTools.LIST,
                    SecretaryWorkspaceTools.READ,
                    SecretaryWorkspaceTools.NOTE);
            resourceScopes.put(SecretaryWorkspaceStore.RESOURCE_NAMESPACE,
                    ResourceScope.of("workspace"));
        }
        if (postService != null) {
            permissionBuilder.allow(SecretaryPostTools.LIST,
                    SecretaryPostTools.READ,
                    SecretaryPostTools.CREATE);
            resourceScopes.put(PostStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(PostStore.RESOURCE_NAMESPACE));
        }
        if (!resourceScopes.isEmpty()) {
            permissionBuilder.resourceScopes(new ResourceScopeMap(resourceScopes));
        }
        this.permissions = permissionBuilder.build();
        this.runner = new AgentRunner(llm, ToolCallAuthorizer.standard(), 8,
                Duration.ofSeconds(60), 8);
    }

    public String playerId() {
        return playerId;
    }

    /** 当前会话可用的工具快照（只读）。 */
    public List<AgentTool> tools() {
        return tools;
    }

    /** 注入的秘书工作区（无注入时为 null）；供宿主装配观察，不进入模型上下文。 */
    public SecretaryWorkspaceStore workspaceStore() {
        return workspaceStore;
    }

    /** 注入的推文服务（无注入时为 null）；供宿主装配观察，不进入模型上下文。 */
    public PostService postService() {
        return postService;
    }

    /** 当前会话的权限集快照（只读）。 */
    public AgentPermissionSet permissions() {
        return permissions;
    }

    /** 仅供宿主集成/测试观察；调用方不得在会话外直接改窗口。 */
    public ContextWindow contextWindow() {
        return window;
    }

    /** 累计的、经授权器执行的工具调用快照。 */
    public synchronized List<ToolInvocation> toolInvocations() {
        return List.copyOf(toolInvocations);
    }

    /**
     * 与当前玩家进行一个私聊回合。
     *
     * @return 模型最终文本；LLM/策略失败时返回明确的中文降级文本
     */
    public synchronized String chat(String userText) {
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText 不能为空白");
        }
        ToolContext base = baseContext();
        AgentRunResult result = runner.runTurn(
                window,
                () -> AgentPrompts.secretary(engine.knowledgeForSecretary(playerId)),
                tools, base, userText);
        return recordAndRender(result);
    }

    /**
     * 处理一条来自已认证 GM 的直接消息。GM 身份由宿主/MCP 认证层绑定；本方法不接收
     * playerId，也不发送 QQ 消息。回复只返回给调用方（宿主/MCP）。
     *
     * <p>与 {@link #chat(String)} 共用同一 {@link ContextWindow} 和同一套工具；system
     * prompt 明确本轮消息来自 GM，不是玩家本人。</p>
     */
    public synchronized String gmChat(String gmMessage) {
        if (gmMessage == null || gmMessage.isBlank()) {
            throw new IllegalArgumentException("gmMessage 不能为空白");
        }
        ToolContext base = baseContext();
        AgentRunResult result = runner.runTurn(
                window,
                () -> AgentPrompts.secretaryGm(engine.knowledgeForSecretary(playerId)),
                tools, base, gmMessage);
        return recordAndRender(result);
    }

    private String recordAndRender(AgentRunResult result) {
        toolInvocations.addAll(result.toolInvocations());
        String text = result.text();
        if (!text.isBlank()) {
            return text;
        }
        if (result.turnResult().stopReason() == StopReason.LLM_ERROR) {
            return "秘书暂时无法回复，请稍后重试。";
        }
        if (result.stoppedByPolicy()) {
            return "秘书处理超时或达到安全上限，请稍后重试。";
        }
        return "秘书暂未生成回复。";
    }

    /**
     * 请求并签发 GM 过滤后的 brief。
     *
     * <p>先只读取 {@code engine.visibleFactsFor(playerId)}，再调用生成器；返回的来源 ID 会与
     * 可见事实集合求交，随后用当前回合 revision 调 {@code requestBrief} 与
     * {@code issueBrief}。</p>
     */
    public synchronized BriefReply requestBrief(String question) {
        if (question == null || question.isBlank()) {
            throw new BriefingFailedException(SecretaryToolSupport.INVALID_ARGUMENTS,
                    "question 不能为空白");
        }
        TurnView before = engine.turnView().orElseThrow(
                () -> new BriefingFailedException("NO_ACTIVE_TURN", "当前没有进行中的回合"));
        if (before.state() != TurnState.OPEN) {
            throw new BriefingFailedException("TURN_NOT_OPEN", "只有 OPEN 回合可以请求 brief");
        }
        List<WorldFact> visibleFacts = engine.visibleFactsFor(playerId);
        Set<String> visibleIds = new LinkedHashSet<>();
        visibleFacts.forEach(fact -> visibleIds.add(fact.factId()));

        BriefingResult generated;
        try {
            generated = briefingService.generate(playerId, question, visibleFacts);
        } catch (RuntimeException failure) {
            throw new BriefingFailedException("BRIEF_GENERATION_FAILED",
                    "brief 生成失败，请稍后重试");
        }
        LinkedHashSet<String> safeSources = new LinkedHashSet<>(generated.sourceFactIds());
        safeSources.retainAll(visibleIds);
        if (generated.content() == null || generated.content().isBlank() || safeSources.isEmpty()) {
            throw new BriefingFailedException("BRIEF_SOURCE_NOT_VISIBLE",
                    "当前没有可引用的玩家可见事实，无法签发 brief");
        }

        CommandResult requested = engine.requestBrief(player, question, before.revision(),
                SecretaryToolSupport.idempotencyKey("brief-request"));
        if (!requested.ok()) {
            throw new BriefingFailedException(requested.code().name(), requested.message());
        }
        String requestId = requested.referenceId().orElseThrow(
                () -> new BriefingFailedException("BRIEF_REQUEST_NOT_FOUND", "引擎回执缺少 requestId"));
        TurnView afterRequest = engine.turnView().orElseThrow(
                () -> new BriefingFailedException("NO_ACTIVE_TURN", "当前没有进行中的回合"));
        CommandResult issued = engine.issueBrief(briefIssuer, requestId, generated.content(),
                safeSources, clock.instant().plus(briefTtl), afterRequest.revision(),
                SecretaryToolSupport.idempotencyKey("brief-issue"));
        if (!issued.ok()) {
            throw new BriefingFailedException(issued.code().name(), issued.message());
        }
        InfoBrief brief = engine.briefsForPlayer(playerId).stream()
                .filter(item -> item.contentHash().equals(
                        issued.details().get("contentHash")))
                .findFirst()
                .orElse(null);
        String content = brief == null ? generated.content() : brief.content();
        return new BriefReply(requestId, content, safeSources);
    }

    private ToolContext baseContext() {
        TurnView turn = engine.turnView().orElse(null);
        String turnId = turn == null ? "" : turn.turnId();
        Map<String, Object> config = Map.of(
                SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, player,
                SecretaryToolContext.TURN_ID, turnId,
                SecretaryToolContext.DRAFT_TTL, draftTtl);
        return new ToolContext(AccessToken.DEFAULT, permissions, config, Map.of());
    }
}
