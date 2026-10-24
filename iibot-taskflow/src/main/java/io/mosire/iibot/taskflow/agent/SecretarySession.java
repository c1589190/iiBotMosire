package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
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
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheException;
import io.mosire.iibot.taskflow.cache.SecretaryCacheStore;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStore;
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
import java.util.StringJoiner;

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
    private volatile ContextWindow window;
    private final List<AgentTool> tools;
    private final AgentPermissionSet permissions;
    private final SecretaryWorkspaceStore workspaceStore;
    private final PostService postService;
    private final SharedSkillStore sharedSkillStore;
    private final ComplaintService complaintService;
    private final ActionPoolService actionPoolService;
    private final TicketService ticketService;
    private final SecretaryCacheStore cacheStore;
    private volatile String activeCacheId;
    private volatile String cacheError;
    private final List<ToolInvocation> toolInvocations = new ArrayList<>();
    private boolean firstPlayerTurn = true;

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

    /** 只注入全局共享 Skill 区；不注入玩家工作区或推文服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SharedSkillStore sharedSkillStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null, sharedSkillStore);
    }

    /** 同时注入工作区、推文服务与共享 Skill 区。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore);
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

    /** 显式有效期 + 仅共享 Skill 区。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SharedSkillStore sharedSkillStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, null, null, sharedSkillStore);
    }

    /** 工作区、推文服务与共享 Skill 区同时注入，store 置于有效期参数之后。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, workspaceStore, postService, null);
    }

    /** 工作区、推文服务与共享 Skill 区同时注入，store 置于有效期参数之后。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService, SharedSkillStore sharedSkillStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                draftTtl, briefTtl, workspaceStore, postService, sharedSkillStore, null);
    }

    /** 只注入投诉服务；未注入工作区、推文或共享 Skill 区。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null, null,
                complaintService);
    }

    /** 同时注入秘书工作区与投诉服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, null, null,
                complaintService);
    }

    /** 同时注入推文服务与投诉服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, PostService postService,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, postService, null,
                complaintService);
    }

    /** 同时注入共享 Skill 区与投诉服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, SharedSkillStore sharedSkillStore,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null, sharedSkillStore,
                complaintService);
    }

    /** 同时注入秘书工作区、推文服务与投诉服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                null, complaintService);
    }

    /** 工作区、推文服务、共享 Skill 区与投诉服务同时注入。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, complaintService);
    }

    /** 只注入行动池；其他可选服务为空。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null, null, null,
                actionPoolService);
    }

    /** 同时注入秘书工作区与行动池。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, null, null,
                null, actionPoolService);
    }

    /** 同时注入推文服务与行动池。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, PostService postService,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, postService, null,
                null, actionPoolService);
    }

    /** 同时注入投诉服务与行动池。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, ComplaintService complaintService,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null, null,
                complaintService, actionPoolService);
    }

    /** 工作区、推文服务、共享 Skill 区、投诉服务与行动池同时注入。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore, ComplaintService complaintService,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, complaintService, actionPoolService);
    }

    /** 工作区、推文服务与行动池同时注入。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                null, null, actionPoolService);
    }

    /** 仅注入统一工单服务；完整 Ticket 装配下旧 complaint 工具不暴露。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), null, null, null,
                null, null, ticketService);
    }

    /** 工作区、推文、共享 Skill、投诉/行动池兼容服务与统一 Ticket 服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore, ComplaintService complaintService,
                            ActionPoolService actionPoolService, TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, complaintService, actionPoolService, ticketService);
    }

    /** 工作区、推文、共享 Skill、投诉/行动池兼容服务、统一 Ticket 与持久化 cache。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore, ComplaintService complaintService,
                            ActionPoolService actionPoolService, TicketService ticketService,
                            SecretaryCacheStore cacheStore) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, complaintService, actionPoolService, ticketService, cacheStore);
    }

    /** 工作区、推文、共享 Skill、旧投诉与统一 Ticket 服务（无行动池）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore, ComplaintService complaintService,
                            TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, complaintService, null, ticketService);
    }

    /** 工作区、推文、共享 Skill、行动池与统一 Ticket 服务（无旧投诉）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore, ActionPoolService actionPoolService,
                            TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, null, actionPoolService, ticketService);
    }

    /** 工作区、推文、旧投诉、行动池与统一 Ticket 服务（无共享 Skill）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            ComplaintService complaintService, ActionPoolService actionPoolService,
                            TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                null, complaintService, actionPoolService, ticketService);
    }

    /** 工作区、推文、共享 Skill 与统一 Ticket 服务（无旧投诉/行动池）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock,
                            SecretaryWorkspaceStore workspaceStore, PostService postService,
                            SharedSkillStore sharedSkillStore, TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15), workspaceStore, postService,
                sharedSkillStore, null, null, ticketService);
    }

    /** 全部可选域服务与显式有效期同时注入（旧构造器，无行动池）。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService, SharedSkillStore sharedSkillStore,
                            ComplaintService complaintService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock, draftTtl,
                briefTtl, workspaceStore, postService, sharedSkillStore, complaintService,
                null);
    }

    /** 全部可选域服务与显式有效期同时注入，并可选接入行动池。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService, SharedSkillStore sharedSkillStore,
                            ComplaintService complaintService,
                            ActionPoolService actionPoolService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock, draftTtl,
                briefTtl, workspaceStore, postService, sharedSkillStore, complaintService,
                actionPoolService, null);
    }

    /** 全部可选域服务与显式有效期同时注入，并可选接入行动池与统一 Ticket 服务。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService, SharedSkillStore sharedSkillStore,
                            ComplaintService complaintService,
                            ActionPoolService actionPoolService, TicketService ticketService) {
        this(playerId, player, engine, llm, briefingService, briefIssuer, clock, draftTtl,
                briefTtl, workspaceStore, postService, sharedSkillStore, complaintService,
                actionPoolService, ticketService, null);
    }

    /** 完整装配：在上一构造器基础上接入持久化会话 cache。 */
    public SecretarySession(String playerId, Actor player, TaskflowEngine engine,
                            LlmClient llm, BriefingService briefingService,
                            Actor briefIssuer, Clock clock, Duration draftTtl,
                            Duration briefTtl, SecretaryWorkspaceStore workspaceStore,
                            PostService postService, SharedSkillStore sharedSkillStore,
                            ComplaintService complaintService,
                            ActionPoolService actionPoolService, TicketService ticketService,
                            SecretaryCacheStore cacheStore) {
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
        this.sharedSkillStore = sharedSkillStore;
        this.complaintService = complaintService;
        this.actionPoolService = actionPoolService;
        this.ticketService = ticketService;
        this.cacheStore = cacheStore;
        CacheSummary initialCache = null;
        String initialCacheError = null;
        List<LlmMessage> initialMessages = List.of();
        if (cacheStore != null) {
            try {
                initialCache = cacheStore.ensureDefaultCache(playerId);
                initialMessages = cacheStore.loadMessages(playerId, initialCache.cacheId());
            } catch (RuntimeException failure) {
                initialCacheError = SecretaryCacheException.CACHE_UNAVAILABLE;
            }
        }
        this.activeCacheId = initialCache == null ? null : initialCache.cacheId();
        this.cacheError = initialCacheError;
        this.window = initialCache == null
                ? new ContextWindow("secretary:" + playerId)
                : new ContextWindow("secretary:" + playerId + ":" + initialCache.cacheId(),
                        initialMessages);
        this.firstPlayerTurn = initialMessages.isEmpty();

        List<AgentTool> availableTools = new ArrayList<>();
        if (ticketService == null) {
            // 旧 DecisionDraft 仅在未注入统一 TicketService 的兼容装配中保留，
            // 并保持原有工具顺序/brief 位置不变。
            availableTools.add(new DecisionDraftCreateTool(engine, clock));
            availableTools.add(new DecisionDraftUpdateTool(engine));
            availableTools.add(new RequestBriefTool(this::requestBrief));
        } else {
            availableTools.add(new RequestBriefTool(this::requestBrief));
        }
        if (workspaceStore != null) {
            availableTools.addAll(SecretaryWorkspaceTools.all(workspaceStore));
        }
        if (sharedSkillStore != null) {
            availableTools.addAll(SecretarySharedSkillTools.all(sharedSkillStore));
        }
        if (postService != null) {
            availableTools.addAll(SecretaryPostTools.all(postService));
        }
        if (ticketService != null) {
            // 完整 Ticket 装配取代旧 complaint 工具，避免双状态主路径。
            availableTools.addAll(SecretaryTicketTools.all(ticketService));
        } else if (complaintService != null) {
            availableTools.addAll(SecretaryComplaintTools.all(complaintService));
        }
        if (ticketService == null && actionPoolService != null) {
            availableTools.addAll(SecretaryActionTools.all(actionPoolService));
        }
        if (cacheStore != null) {
            availableTools.addAll(SecretaryCacheTools.all(this));
        }
        this.tools = List.copyOf(availableTools);

        AgentPermissionSet.Builder permissionBuilder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(RequestBriefTool.NAME)
                .roles(RoleSet.of(SECRETARY_ROLE));
        if (ticketService == null) {
            permissionBuilder.allow(DecisionDraftCreateTool.NAME, DecisionDraftUpdateTool.NAME);
        }
        if (ticketService != null || complaintService != null
                || (ticketService == null && actionPoolService != null)) {
            // 工单/投诉工具与行动池 submit 声明 sensitive=true；只对显式装配的会话放行敏感位。
            permissionBuilder.sensitiveAllowed(true);
        }
        Map<String, ResourceScope> resourceScopes = new LinkedHashMap<>();
        if (workspaceStore != null) {
            permissionBuilder.allow(SecretaryWorkspaceTools.LIST,
                    SecretaryWorkspaceTools.READ,
                    SecretaryWorkspaceTools.NOTE);
            resourceScopes.put(SecretaryWorkspaceStore.RESOURCE_NAMESPACE,
                    ResourceScope.of("workspace"));
        }
        if (sharedSkillStore != null) {
            permissionBuilder.allow(SecretarySharedSkillTools.LIST,
                    SecretarySharedSkillTools.READ);
            resourceScopes.put(SharedSkillStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(SharedSkillStore.RESOURCE_NAMESPACE));
        }
        if (cacheStore != null) {
            permissionBuilder.allow(SecretaryCacheTools.LIST, SecretaryCacheTools.READ);
            resourceScopes.put(SecretaryCacheStore.RESOURCE_NAMESPACE,
                    ResourceScope.of("conversation"));
        }
        if (postService != null) {
            permissionBuilder.allow(SecretaryPostTools.LIST,
                    SecretaryPostTools.READ,
                    SecretaryPostTools.CREATE);
            resourceScopes.put(PostStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(PostStore.RESOURCE_NAMESPACE));
        }
        if (ticketService != null) {
            permissionBuilder.allow(SecretaryTicketTools.SUBMIT,
                    SecretaryTicketTools.LIST,
                    SecretaryTicketTools.READ);
            resourceScopes.put(TicketStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(TicketStore.RESOURCE_NAMESPACE));
        } else if (complaintService != null) {
            permissionBuilder.allow(SecretaryComplaintTools.SUBMIT,
                    SecretaryComplaintTools.LIST,
                    SecretaryComplaintTools.READ);
            resourceScopes.put(ComplaintStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(ComplaintStore.RESOURCE_NAMESPACE));
        }
        if (ticketService == null && actionPoolService != null) {
            permissionBuilder.allow(SecretaryActionTools.SUBMIT,
                    SecretaryActionTools.LIST,
                    SecretaryActionTools.WITHDRAW);
            resourceScopes.put(ActionPoolStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(ActionPoolStore.RESOURCE_NAMESPACE));
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

    /** 注入的全局共享 Skill 区（无注入时为 null）；供宿主装配观察，不进入模型上下文。 */
    public SharedSkillStore sharedSkillStore() {
        return sharedSkillStore;
    }

    /** 注入的持久化 cache（无注入时为 null）。 */
    public SecretaryCacheStore cacheStore() {
        return cacheStore;
    }

    /** 注入的投诉服务（无注入时为 null）；供宿主装配观察，不进入模型上下文。 */
    public ComplaintService complaintService() {
        return complaintService;
    }

    /** 注入的行动池服务（无注入时为 null）；供宿主装配观察，不进入模型上下文。 */
    public ActionPoolService actionPoolService() {
        return actionPoolService;
    }

    /** 注入的统一 Ticket 服务（无注入时为 null）；供宿主装配观察，不进入模型上下文。 */
    public TicketService ticketService() {
        return ticketService;
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

    /** 当前 active cacheId；未装配 cache 时为 null。 */
    public String activeCacheId() {
        return activeCacheId;
    }

    /** READY / CONTEXT_LOST / DISABLED。 */
    public String cacheStatus() {
        if (cacheStore == null) {
            return "DISABLED";
        }
        return cacheError == null ? "READY" : "CONTEXT_LOST";
    }

    /** 当前玩家可见的 cache 摘要；跨玩家读取由 cache store 的 owner 校验兜底。 */
    public synchronized List<CacheSummary> cacheSummaries() {
        requireCacheReady();
        return cacheStore.listCaches(playerId);
    }

    /** 为当前玩家新建一个空 cache；不自动切换 active。 */
    public synchronized CacheSummary createCache(String title) {
        requireCacheReady();
        String safeTitle = title == null || title.isBlank() ? "新对话" : title.trim();
        try {
            return cacheStore.createCache(playerId, safeTitle, "APP");
        } catch (SecretaryCacheException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            cacheError = SecretaryCacheException.CACHE_WRITE_FAILED;
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_WRITE_FAILED,
                    "创建会话 cache 失败");
        }
    }

    /**
     * 切换当前玩家的 active cache。旧窗口先落库；任何一步失败都保留旧窗口。
     */
    public synchronized void switchCache(String cacheId) {
        requireCacheReady();
        if (cacheId == null || cacheId.isBlank()) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    "cacheId 不能为空白");
        }
        CacheSummary target;
        try {
            target = cacheStore.findCache(playerId, cacheId.trim())
                    .orElseThrow(() -> new SecretaryCacheException(
                            SecretaryCacheException.CACHE_NOT_FOUND, "cache 不存在"));
        } catch (SecretaryCacheException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "读取 cache 失败");
        }
        if (!playerId.equals(target.playerId())) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_OWNER_MISMATCH,
                    "cache 不属于当前玩家");
        }
        List<LlmMessage> targetMessages;
        try {
            targetMessages = cacheStore.loadMessages(playerId, target.cacheId());
        } catch (RuntimeException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "读取目标 cache 失败");
        }
        ContextWindow targetWindow = new ContextWindow(
                "secretary:" + playerId + ":" + target.cacheId(), targetMessages);
        persistPlayerWindowOrThrow();
        try {
            cacheStore.setActiveCache(playerId, target.cacheId());
        } catch (RuntimeException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_WRITE_FAILED,
                    "切换 active cache 失败");
        }
        this.activeCacheId = target.cacheId();
        this.window = targetWindow;
        this.firstPlayerTurn = targetMessages.isEmpty();
        this.cacheError = null;
    }

    /**
     * 读取一页当前玩家可见的历史对话。只渲染 user/assistant 文本；tool_call/tool_result
     * 不进入工具结果，避免把内部参数和未展示内容重新注入模型。
     */
    public synchronized String readCacheTranscript(String cacheId, int limit) {
        requireCacheReady();
        if (limit < 1 || limit > 50) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    "limit 必须在 1–50 之间");
        }
        String targetId = cacheId == null || cacheId.isBlank()
                ? activeCacheId : cacheId.trim();
        if (targetId == null || targetId.isBlank()) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "当前没有 active cache");
        }
        CacheSummary summary;
        try {
            summary = cacheStore.findCache(playerId, targetId)
                    .orElseThrow(() -> new SecretaryCacheException(
                            SecretaryCacheException.CACHE_NOT_FOUND, "cache 不存在"));
        } catch (SecretaryCacheException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "读取 cache 失败");
        }
        if (!playerId.equals(summary.playerId())) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_OWNER_MISMATCH,
                    "cache 不属于当前玩家");
        }
        List<LlmMessage> messages;
        try {
            messages = cacheStore.loadMessages(playerId, targetId);
        } catch (RuntimeException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "读取 cache 消息失败");
        }
        return renderTranscript(summary, messages, limit);
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
        if (cacheStore != null && cacheError != null) {
            return "秘书历史上下文暂不可用，请稍后重试。";
        }
        ToolContext base = baseContext();
        String effectiveInput = firstPlayerTurn
                ? firstTurnReminder() + userText : userText;
        AgentRunResult result = runner.runTurn(
                window,
                () -> AgentPrompts.secretary(engine.knowledgeForSecretary(playerId)),
                tools, base, effectiveInput);
        firstPlayerTurn = false;
        try {
            persistPlayerWindow();
        } catch (SecretaryCacheException ignored) {
            // 回复已经产生；cache 失败状态由 cacheStatus/read 工具明确暴露。
        }
        return recordAndRender(result, true);
    }

    /**
     * 处理一条来自已认证 GM 的直接消息。GM 身份由宿主/MCP 认证层绑定；本方法不接收
     * playerId，也不发送 QQ 消息。回复只返回给调用方（宿主/MCP）。
     *
     * <p>GM 使用独立的临时窗口，以玩家当前消息作为只读种子；GM 消息不会写回玩家
     * cache，也不会污染玩家后续回合。</p>
     */
    public synchronized String gmChat(String gmMessage) {
        if (gmMessage == null || gmMessage.isBlank()) {
            throw new IllegalArgumentException("gmMessage 不能为空白");
        }
        ToolContext base = baseContext();
        ContextWindow debugWindow = new ContextWindow(
                "secretary:" + playerId + ":gm", window.messages());
        AgentRunResult result = runner.runTurn(
                debugWindow,
                () -> AgentPrompts.secretaryGm(engine.knowledgeForSecretary(playerId)),
                tools, base,
                "【宿主说明】消息来自 GM，不是玩家；不是玩家硬确认。\n\n"
                        + gmMessage);
        return recordAndRender(result, false);
    }

    private void requireCacheReady() {
        if (cacheStore == null) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "当前秘书未装配持久化 cache");
        }
        if (cacheError != null) {
            throw new SecretaryCacheException(cacheError, "秘书历史上下文不可用");
        }
    }

    private void persistPlayerWindow() {
        persistPlayerWindowOrThrow();
    }

    private void persistPlayerWindowOrThrow() {
        if (cacheStore == null) {
            return;
        }
        if (cacheError != null) {
            throw new SecretaryCacheException(cacheError, "秘书历史上下文不可用");
        }
        if (activeCacheId == null || activeCacheId.isBlank()) {
            cacheError = SecretaryCacheException.CACHE_UNAVAILABLE;
            throw new SecretaryCacheException(cacheError, "当前没有 active cache");
        }
        List<LlmMessage> tail = window.unsavedTail();
        if (tail.isEmpty()) {
            return;
        }
        try {
            cacheStore.appendMessages(playerId, activeCacheId, tail);
            window.markSaved();
        } catch (RuntimeException failure) {
            cacheError = SecretaryCacheException.CACHE_WRITE_FAILED;
            throw new SecretaryCacheException(cacheError, "保存会话 cache 失败");
        }
    }

    private static String renderTranscript(CacheSummary summary, List<LlmMessage> messages,
                                           int limit) {
        List<String> rendered = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (!LlmMessage.ROLE_USER.equals(message.role())
                    && !LlmMessage.ROLE_ASSISTANT.equals(message.role())) {
                continue;
            }
            String text = textOf(message);
            if (LlmMessage.ROLE_USER.equals(message.role())) {
                text = stripFirstTurnReminder(text);
            }
            if (text.isBlank()) {
                continue;
            }
            String role = LlmMessage.ROLE_USER.equals(message.role()) ? "玩家" : "秘书";
            rendered.add("[" + role + "] " + text);
        }
        int from = Math.max(0, rendered.size() - limit);
        List<String> page = rendered.subList(from, rendered.size());
        StringJoiner out = new StringJoiner("\n\n");
        out.add("cacheId=" + summary.cacheId() + " | title=" + summary.title()
                + " | active=" + summary.active() + " | messages=" + messages.size()
                + " | returned=" + page.size());
        if (page.isEmpty()) {
            out.add("（该 cache 没有可读取的文本对话）");
        } else {
            for (String line : page) {
                out.add(line);
            }
        }
        return out.toString();
    }

    private static String textOf(LlmMessage message) {
        return message.content().stream()
                .filter(ContentPart.Text.class::isInstance)
                .map(ContentPart.Text.class::cast)
                .map(ContentPart.Text::text)
                .reduce("", (left, right) -> left + (left.isEmpty() ? "" : "\n") + right);
    }

    private static String stripFirstTurnReminder(String text) {
        String marker = "\n\n玩家消息：\n";
        if (text.startsWith("【宿主提醒：第一轮】")) {
            int index = text.indexOf(marker);
            if (index >= 0) {
                return text.substring(index + marker.length());
            }
        }
        return text;
    }

    /**
     * 第一轮玩家消息的宿主提醒：只注入 user 字段，不修改 system prompt。
     *
     * <p>玩家只有 QQ 聊天窗口，不能读工作区。首轮必须把当前身份/局势/可行动方向直接
     * 在聊天里给出，而不是要求玩家打开文件。</p>
     */
    private String firstTurnReminder() {
        StringBuilder reminder = new StringBuilder("""
                【宿主提醒：第一次与玩家交流】
                1. 先调用 secretary_shared_skill_list / secretary_shared_skill_read 读取相关共享 Skill。
                2. 再调用 secretary_workspace_read 读取工作区 AGENTS.md（path=AGENTS.md）。
                3. 严格按 Skill 与 AGENTS.md 中的交互规则处理本轮；不要把文件路径、内部规则或后台字段发给玩家。
                4. 下面只提供已经过可见性过滤的玩家资料；资料不足时明确说明，不要编造。

                【玩家可见资料】
                """);
        SecretaryKnowledge knowledge = engine.knowledgeForSecretary(playerId);
        if (knowledge.profile() != null) {
            reminder.append("- 玩家称呼：").append(knowledge.profile().displayName()).append('\n');
        }
        if (!knowledge.turnSummary().isBlank()) {
            reminder.append("- 当前局势：").append(knowledge.turnSummary()).append('\n');
        }
        int used = 0;
        for (WorldFact fact : knowledge.visibleFacts()) {
            String content = fact.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > 1_000) {
                content = content.substring(0, 1_000) + "……";
            }
            if (used + content.length() > 8_000) {
                reminder.append("- 其余资料按需从工作区读取。\n");
                break;
            }
            reminder.append("- 可见事实：").append(content).append('\n');
            used += content.length();
        }
        for (InfoBrief brief : knowledge.briefs()) {
            reminder.append("- 玩家简报：").append(brief.content()).append('\n');
        }
        reminder.append("\n玩家消息：\n");
        return reminder.toString();
    }

    private String recordAndRender(AgentRunResult result, boolean playerFacing) {
        toolInvocations.addAll(result.toolInvocations());
        String text = result.text();
        if (text != null && !text.isBlank()) {
            return playerFacing ? PlayerFacingTextGuard.normalizeForChat(text) : text;
        }
        if (result.turnResult().stopReason() == StopReason.LLM_ERROR) {
            return playerFacing
                    ? "我这边暂时没接上思路。你把刚才的意思再说一句，我马上接着处理。"
                    : "秘书暂时无法回复，请稍后重试。";
        }
        if (result.stoppedByPolicy()) {
            return playerFacing
                    ? "刚才处理太久了，我先停一下。你可以把最重要的一步再说一次。"
                    : "秘书处理超时或达到安全上限，请稍后重试。";
        }
        return playerFacing ? "我还没组织好怎么回答。你再给我一句话，我直接说重点。"
                : "秘书暂未生成回复。";
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
