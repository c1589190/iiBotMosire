package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnSnapshot;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldFact;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 中央推演服务：从冻结快照与全量事实出发，用独立窗口运行 GM Agent。
 *
 * <p>秘书会话拿不到本服务提供的全量事实；本服务也不会把 GM prompt/工具暴露给秘书。唯一工具
 * {@code submit_adjudication} 只生成结构化提案并交 {@code engine.commitAdjudication}，不直接
 * 改世界。若 AgentLoop 未提交，服务会调用 {@code failAdjudication} 进入可重试态，或返回明确
 * 失败；此时 worldVersion 不变。</p>
 */
public final class AdjudicationService {

    private final TaskflowEngine engine;
    private final AgentRunner runner;

    public AdjudicationService(TaskflowEngine engine, LlmClient llm) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.runner = new AgentRunner(Objects.requireNonNull(llm, "llm"),
                ToolCallAuthorizer.standard(), 8, Duration.ofSeconds(120), 4);
    }

    public AdjudicationService(TaskflowEngine engine, LlmClient llm,
                               ToolCallAuthorizer authorizer, int maxRounds,
                               Duration timeBudget) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.runner = new AgentRunner(Objects.requireNonNull(llm, "llm"), authorizer,
                maxRounds, timeBudget, 4);
    }

    /**
     * 启动/继续推演并运行 GM Agent。
     *
     * @param gm         已认证的 GM/DIRECTOR/SUPERVISOR
     * @param worldFacts 调用方提供的全量世界事实；本服务不会从秘书知识面取
     */
    public AdjudicationResult adjudicate(Actor gm, List<WorldFact> worldFacts) {
        Objects.requireNonNull(gm, "gm");
        Objects.requireNonNull(worldFacts, "worldFacts");
        if (!isOperator(gm.role())) {
            throw new IllegalArgumentException("只有 GM/DIRECTOR/SUPERVISOR 可以推演");
        }
        TurnView initial = engine.turnView().orElse(null);
        if (initial == null) {
            return new AdjudicationResult(false, "none", TurnState.CANCELLED, "",
                    "当前没有进行中的回合", List.of());
        }
        if (initial.state() == TurnState.LOCKED) {
            CommandResult started = engine.startAdjudication(gm, initial.revision(),
                    "adjudication-start-" + UUID.randomUUID());
            if (!started.ok()) {
                return failure(initial.turnId(), started.turnState(), "",
                        "启动推演失败: " + started.code() + " " + started.message(), List.of());
            }
        } else if (initial.state() == TurnState.FAILED_RETRYABLE) {
            CommandResult retried = engine.retryAdjudication(gm, initial.revision(),
                    "adjudication-retry-" + UUID.randomUUID());
            if (!retried.ok()) {
                return failure(initial.turnId(), retried.turnState(), "",
                        "重试推演失败: " + retried.code() + " " + retried.message(), List.of());
            }
        } else if (initial.state() != TurnState.ADJUDICATING) {
            return failure(initial.turnId(), initial.state(), "",
                    "回合状态 " + initial.state() + " 不能启动推演", List.of());
        }

        TurnView active = engine.turnView().orElseThrow();
        TurnSnapshot snapshot = active.snapshot();
        if (snapshot == null) {
            return failure(active.turnId(), active.state(), "",
                    "回合缺少冻结快照，不能推演", List.of());
        }

        String roleLabel = gm.role().name().toLowerCase();
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SubmitAdjudicationTool.NAME)
                .sensitiveAllowed(true)
                .destructiveAllowed(true)
                .roles(RoleSet.of(roleLabel))
                .build();
        SubmitAdjudicationTool tool = new SubmitAdjudicationTool(engine, gm,
                snapshot.turnId(), snapshot.worldVersion());
        ContextWindow window = new ContextWindow("adjudication:" + snapshot.turnId()
                + ":" + UUID.randomUUID());
        String systemPrompt = AgentPrompts.adjudicator(gm, snapshot, worldFacts);
        String userInstruction = "请根据冻结决策和全量世界事实，提交一份结构化推演提案。"
                + "必须调用 submit_adjudication；提交后如果需要说明，再简短总结。";

        AgentRunResult run = runner.runTurn(window, systemPrompt, List.of(tool),
                new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), Map.of()),
                userInstruction);

        TurnView after = engine.turnView().orElse(active);
        if (after.state() == TurnState.COMMITTED) {
            return new AdjudicationResult(true, after.turnId(), after.state(),
                    run.text(), "", run.toolInvocations());
        }

        String reason = "AgentLoop 未提交有效推演提案"
                + "（" + run.turnResult().stopReason() + "，工具调用 "
                + run.toolInvocations().size() + " 次）";
        if (after.state() == TurnState.ADJUDICATING) {
            CommandResult failed = engine.failAdjudication(gm, reason, after.revision(),
                    "adjudication-fail-" + UUID.randomUUID());
            TurnView finalView = engine.turnView().orElse(after);
            if (!failed.ok()) {
                reason += "；标记失败也失败: " + failed.code();
            }
            return failure(after.turnId(), finalView.state(), run.text(), reason,
                    run.toolInvocations());
        }
        return failure(after.turnId(), after.state(), run.text(), reason,
                run.toolInvocations());
    }

    /** 兼容命令层：只关心是否提交。 */
    public boolean isCommitted() {
        return engine.turnView().map(view -> view.state() == TurnState.COMMITTED).orElse(false);
    }

    private static AdjudicationResult failure(String turnId, TurnState state, String text,
                                              String reason, List<ToolInvocation> invocations) {
        return new AdjudicationResult(false, turnId, state, text, reason, invocations);
    }

    private static boolean isOperator(ActorRole role) {
        return role == ActorRole.GM || role == ActorRole.DIRECTOR
                || role == ActorRole.SUPERVISOR;
    }
}
