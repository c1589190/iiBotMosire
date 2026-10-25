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
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.control.EventReadPort;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外部 GM 专用推演秘书 Agent。
 *
 * <p>GM 推演秘书拥有自己的 {@link ContextWindow} 与独立 {@link LlmClient}（组合根按
 * {@code gmAide} route 注入），只挂载 {@link GmAideTools} 的只读工具。它可读取全量
 * 世界资料并向 MCP/GM 调用者整理信息、推演动向，但没有 QQ 发送、世界写、决策确认、
 * 文件写、进程或密钥工具；返回文本只交给调用方，不进入任何玩家秘书上下文。</p>
 */
public final class GmAideAgent {

    /** GM 推演秘书唯一角色标签。 */
    public static final String ROLE = "gm-aide";

    public static final String GM_WORLD_FACTS = GmAideTools.GM_WORLD_FACTS;
    public static final String GM_TURN_VIEW = GmAideTools.GM_TURN_VIEW;
    public static final String GM_PROFILES = GmAideTools.GM_PROFILES;
    public static final String GM_BRIEFS = GmAideTools.GM_BRIEFS;
    public static final String GM_EVENT_POLL = GmAideTools.GM_EVENT_POLL;
    public static final String GM_WORKSPACE_LIST = GmAideTools.GM_WORKSPACE_LIST;
    public static final String GM_WORKSPACE_READ = GmAideTools.GM_WORKSPACE_READ;
    public static final String GM_POSTS = GmAideTools.GM_POSTS;
    public static final String GM_POST_READ = GmAideTools.GM_POST_READ;
    public static final String GM_SHARED_SKILLS = GmAideTools.GM_SHARED_SKILLS;
    public static final String GM_SHARED_SKILL_READ = GmAideTools.GM_SHARED_SKILL_READ;
    public static final String GM_COMPLAINTS = GmAideTools.GM_COMPLAINTS;
    public static final String GM_COMPLAINT_READ = GmAideTools.GM_COMPLAINT_READ;

    private final TaskflowEngine engine;
    private final AgentRunner runner;
    private final ContextWindow window = new ContextWindow("gm-aide");
    private final List<AgentTool> tools;
    private final AgentPermissionSet permissions;
    private final SecretaryWorkspaceStore workspaceStore;
    private final PostService postService;
    private final SharedSkillStore sharedSkillStore;
    private final ComplaintService complaintService;
    private final List<ToolInvocation> toolInvocations = new ArrayList<>();

    /** 不注入事件端口、工作区、推文服务与共享 Skill 区；只产生基础只读工具。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm) {
        this(engine, llm, null, null, null, null,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 注入可选只读事件端口；不注入工作区/推文服务/共享 Skill 区。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort) {
        this(engine, llm, eventReadPort, null, null, null,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 只注入全局共享 Skill 区；不注入事件端口、工作区或推文服务。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       SharedSkillStore sharedSkillStore) {
        this(engine, llm, null, null, null, sharedSkillStore,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 注入只读事件端口与全局共享 Skill 区。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SharedSkillStore sharedSkillStore) {
        this(engine, llm, eventReadPort, null, null, sharedSkillStore,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 不注入事件端口，但允许 GM aide 全量只读工作区与推文。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       SecretaryWorkspaceStore workspaceStore, PostService postService) {
        this(engine, llm, null, workspaceStore, postService, null,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 不注入事件端口，但允许 GM aide 只读工作区、推文与共享 Skill 区。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       SharedSkillStore sharedSkillStore) {
        this(engine, llm, null, workspaceStore, postService, sharedSkillStore,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 注入只读事件端口、工作区与推文服务；工具面仍是只读集合。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SecretaryWorkspaceStore workspaceStore, PostService postService) {
        this(engine, llm, eventReadPort, workspaceStore, postService, null,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 注入只读事件端口、工作区、推文服务与共享 Skill 区；工具面仍是只读集合。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       SharedSkillStore sharedSkillStore) {
        this(engine, llm, eventReadPort, workspaceStore, postService, sharedSkillStore,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 不注入事件端口，但显式注入授权器与循环预算。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       ToolCallAuthorizer authorizer, int maxRounds, Duration timeBudget) {
        this(engine, llm, null, workspaceStore, postService, null,
                authorizer, maxRounds, timeBudget);
    }

    /** 不注入事件端口，但显式注入共享 Skill 区、授权器与循环预算。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       SharedSkillStore sharedSkillStore,
                       ToolCallAuthorizer authorizer, int maxRounds, Duration timeBudget) {
        this(engine, llm, null, workspaceStore, postService, sharedSkillStore,
                authorizer, maxRounds, timeBudget);
    }

    /** 测试/高级装配（不含工作区/推文服务）；工具面仍固定为只读集合。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       ToolCallAuthorizer authorizer, int maxRounds, Duration timeBudget) {
        this(engine, llm, eventReadPort, null, null, null,
                authorizer, maxRounds, timeBudget);
    }

    /** 测试/高级装配（事件端口 + 共享 Skill 区）；工具面仍固定为只读集合。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SharedSkillStore sharedSkillStore,
                       ToolCallAuthorizer authorizer, int maxRounds, Duration timeBudget) {
        this(engine, llm, eventReadPort, null, null, sharedSkillStore,
                authorizer, maxRounds, timeBudget);
    }

    /**
     * 测试/高级装配：授权器与循环预算显式注入，但工具面仍固定为只读集合。
     *
     * <p>工作区、推文服务与共享 Skill 区都是可选依赖：为 null 时对应工具不出现。非 null 时
     * GM aide 在自身权限集里获得对应命名空间的显式只读可达面，具体调用仍由
     * {@link ToolCallAuthorizer} 逐次判定。</p>
     */
    /** 注入事件端口、工作区、推文、共享 Skill 与投诉服务；工具面仍是只读集合。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       SharedSkillStore sharedSkillStore, ComplaintService complaintService) {
        this(engine, llm, eventReadPort, workspaceStore, postService, sharedSkillStore,
                complaintService, ToolCallAuthorizer.standard(),
                AgentRunner.DEFAULT_MAX_ROUNDS, AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 只注入投诉服务；不注入事件端口、工作区、推文或共享 Skill 区。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       ComplaintService complaintService) {
        this(engine, llm, null, null, null, null, complaintService,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 只注入共享 Skill 区与投诉服务；不注入工作区或推文服务。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm,
                       SharedSkillStore sharedSkillStore, ComplaintService complaintService) {
        this(engine, llm, null, null, null, sharedSkillStore, complaintService,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 注入只读事件端口、共享 Skill 区与投诉服务。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SharedSkillStore sharedSkillStore, ComplaintService complaintService) {
        this(engine, llm, eventReadPort, null, null, sharedSkillStore, complaintService,
                ToolCallAuthorizer.standard(), AgentRunner.DEFAULT_MAX_ROUNDS,
                AgentRunner.DEFAULT_TIME_BUDGET);
    }

    /** 测试/高级装配：不含投诉服务；工具面仍固定为只读集合。 */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       SharedSkillStore sharedSkillStore,
                       ToolCallAuthorizer authorizer, int maxRounds, Duration timeBudget) {
        this(engine, llm, eventReadPort, workspaceStore, postService, sharedSkillStore, null,
                authorizer, maxRounds, timeBudget);
    }

    /**
     * 测试/高级装配：授权器与循环预算显式注入，但工具面仍固定为只读集合。
     *
     * <p>工作区、推文服务、共享 Skill 区与投诉服务都是可选依赖：为 null 时对应工具不出现。
     * 非 null 时 GM aide 在自身权限集里获得对应命名空间的显式只读可达面，具体调用仍由
     * {@link ToolCallAuthorizer} 逐次判定。</p>
     */
    public GmAideAgent(TaskflowEngine engine, LlmClient llm, EventReadPort eventReadPort,
                       SecretaryWorkspaceStore workspaceStore, PostService postService,
                       SharedSkillStore sharedSkillStore, ComplaintService complaintService,
                       ToolCallAuthorizer authorizer, int maxRounds, Duration timeBudget) {
        this.engine = Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(llm, "llm");
        this.workspaceStore = workspaceStore;
        this.postService = postService;
        this.sharedSkillStore = sharedSkillStore;
        this.complaintService = complaintService;
        this.tools = GmAideTools.create(engine, eventReadPort, workspaceStore, postService,
                sharedSkillStore, complaintService);
        AgentPermissionSet.Builder permissionBuilder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tools.stream().map(AgentTool::name).toArray(String[]::new))
                .roles(RoleSet.of(ROLE));
        Map<String, ResourceScope> resourceScopes = new LinkedHashMap<>();
        if (workspaceStore != null) {
            resourceScopes.put(SecretaryWorkspaceStore.RESOURCE_NAMESPACE,
                    ResourceScope.unlimited());
        }
        if (postService != null) {
            resourceScopes.put(PostStore.RESOURCE_NAMESPACE, ResourceScope.unlimited());
        }
        if (sharedSkillStore != null) {
            resourceScopes.put(SharedSkillStore.RESOURCE_NAMESPACE, ResourceScope.unlimited());
        }
        if (complaintService != null) {
            resourceScopes.put(ComplaintStore.RESOURCE_NAMESPACE, ResourceScope.unlimited());
        }
        if (!resourceScopes.isEmpty()) {
            permissionBuilder.resourceScopes(new ResourceScopeMap(resourceScopes));
        }
        this.permissions = permissionBuilder.build();
        this.runner = new AgentRunner(llm, Objects.requireNonNull(authorizer, "authorizer"),
                maxRounds, Objects.requireNonNull(timeBudget, "timeBudget"),
                AgentRunner.DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE);
    }

    /** 供宿主/测试核对工具面；返回不可变快照。 */
    public List<AgentTool> tools() {
        return tools;
    }

    /** 独立会话窗口；调用方不得在 {@link #chat(String)} 外修改。 */
    public ContextWindow contextWindow() {
        return window;
    }

    /** 注入的只读工作区；未注入为 null。供组合根/测试核对装配。 */
    public SecretaryWorkspaceStore workspaceStore() {
        return workspaceStore;
    }

    /** 注入的只读推文服务；未注入为 null。供组合根/测试核对装配。 */
    public PostService postService() {
        return postService;
    }

    /** 注入的全局共享 Skill 区；未注入为 null。供组合根/测试核对装配。 */
    public SharedSkillStore sharedSkillStore() {
        return sharedSkillStore;
    }

    /** 注入的投诉服务；未注入为 null。供组合根/测试核对装配。 */
    public ComplaintService complaintService() {
        return complaintService;
    }

    /** 累计的、经授权器实际执行的工具调用。 */
    public synchronized List<ToolInvocation> toolInvocations() {
        return List.copyOf(toolInvocations);
    }

    /**
     * 串行复用一个 GM 推演秘书回合。
     *
     * @return 只返回给 MCP/GM 调用者的整理文本；LLM 或策略失败时返回稳定中文摘要
     */
    public synchronized String chat(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空白");
        }
        AgentRunResult result;
        try {
            result = runner.runTurn(window, AgentPrompts.gmAide(), tools,
                    baseContext(), message);
        } catch (RuntimeException unexpected) {
            return "GM 推演秘书暂时无法回答，请稍后重试。";
        }
        toolInvocations.addAll(result.toolInvocations());
        String text = result.text();
        if (!text.isBlank()) {
            return text;
        }
        if (result.turnResult().stopReason() == StopReason.LLM_ERROR) {
            return "GM 推演秘书暂时无法回答，请稍后重试。";
        }
        if (result.stoppedByPolicy()) {
            return "GM 推演秘书处理超时或达到安全上限，请稍后重试。";
        }
        return "GM 推演秘书暂未形成可返回的整理结果。";
    }

    private ToolContext baseContext() {
        return new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of("agentRoute", "gmAide", "role", ROLE), Map.of());
    }
}
