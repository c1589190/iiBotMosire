package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.runtime.AgentLoop;
import io.mosire.agentlib.runtime.LoopState;
import io.mosire.agentlib.runtime.StopPolicy;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.runtime.StopSignal;
import io.mosire.agentlib.runtime.ToolCallDispatcher;
import io.mosire.agentlib.runtime.TurnResult;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolDefs;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 AgentLib {@link AgentLoop} 的单回合 Agent 运行时。
 *
 * <p>职责边界：本类只负责“从窗口现组请求 → LLM 往返 → 经授权器执行工具 → 回填工具结果 →
 * 按策略终止”。世界状态、QQ 发送和身份提升都不在这里。工具只能经
 * {@link ToolCallAuthorizer#execute} 执行，失败结果也会以
 * {@link LlmMessage#tool(ContentPart.ToolResult)} 写回窗口，保证下一次请求不会留下悬空
 * tool call。</p>
 *
 * <p><b>线程安全边界</b>：{@link ContextWindow} 本身非线程安全。本类在
 * {@code synchronized (window)} 内执行完整回合，允许多个调用方共享同一个窗口但只能串行
 * 进入；同一窗口上的历史读取/改写仍应由宿主统一串行化。窗口历史在每次请求中逐条原样回灌，
 * 只有 system prompt 位于头部。</p>
 */
public final class AgentRunner {

    /** 玩家秘书与 GM 推演秘书的默认最大 LLM 往返轮数。 */
    public static final int DEFAULT_MAX_ROUNDS = 128;
    /** 默认单回合时间预算；128 轮仍须有硬时间上限。 */
    public static final Duration DEFAULT_TIME_BUDGET = Duration.ofMinutes(5);
    /** 单次响应允许派发的工具调用上限。 */
    public static final int DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE = 8;

    private final LlmClient llm;
    private final ToolCallAuthorizer authorizer;
    private final int maxRounds;
    private final Duration timeBudget;
    private final int maxToolCallsPerResponse;

    /** 使用默认授权器、128 轮、5 分钟预算、单响应最多 8 条工具调用。 */
    public AgentRunner(LlmClient llm) {
        this(llm, ToolCallAuthorizer.standard(), DEFAULT_MAX_ROUNDS,
                DEFAULT_TIME_BUDGET, DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE);
    }

    public AgentRunner(LlmClient llm, ToolCallAuthorizer authorizer, int maxRounds,
                       Duration timeBudget, int maxToolCallsPerResponse) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("maxRounds 必须为正");
        }
        if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalArgumentException("timeBudget 必须为正");
        }
        if (maxToolCallsPerResponse < 0) {
            throw new IllegalArgumentException("maxToolCallsPerResponse 不能为负");
        }
        this.maxRounds = maxRounds;
        this.timeBudget = timeBudget;
        this.maxToolCallsPerResponse = maxToolCallsPerResponse;
    }

    /**
     * 跑一个用户文本回合。
     *
     * @param window       可复用的会话窗口；调用期间必须串行
     * @param systemPrompt 每次请求头部的 system prompt
     * @param tools        本轮可执行且可被模型看见的工具
     * @param baseContext  宿主绑定身份/config/权限的上下文；工具参数由本类补齐
     * @param userText     本轮玩家文本
     * @return 终止结果与工具调用审计
     */
    public AgentRunResult runTurn(ContextWindow window, String systemPrompt, List<AgentTool> tools,
                                  ToolContext baseContext, String userText) {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt 不能为空白");
        }
        if (userText == null) {
            throw new IllegalArgumentException("userText 不能为 null");
        }
        return runMessages(window, SystemPromptAssembler.of(systemPrompt), tools,
                baseContext, List.of(LlmMessage.user(userText)));
    }

    /**
     * 组装器版本；每次 LLM 往返重新调用组装器，适合秘书知识随回合变化的场景。
     */
    public AgentRunResult runTurn(ContextWindow window, SystemPromptAssembler systemPrompt,
                                  List<AgentTool> tools, ToolContext baseContext, String userText) {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userText, "userText");
        return runMessages(window, systemPrompt, tools, baseContext,
                List.of(LlmMessage.user(userText)));
    }

    private AgentRunResult runMessages(ContextWindow window, SystemPromptAssembler systemPrompt,
                                       List<AgentTool> tools, ToolContext baseContext,
                                       List<LlmMessage> turnInput) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(baseContext, "baseContext");
        Objects.requireNonNull(turnInput, "turnInput");

        List<AgentTool> toolSnapshot = List.copyOf(tools);
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(toolSnapshot);

        List<ToolInvocation> invocations =
                Collections.synchronizedList(new ArrayList<>());
        ContextComposer composer = (windowMessages, input, requestTools) -> {
            String prompt = systemPrompt.assemble();
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalStateException("SystemPromptAssembler 返回了空白 prompt");
            }
            List<LlmMessage> messages = new ArrayList<>(1 + windowMessages.size() + input.size());
            messages.add(LlmMessage.system(prompt));
            // 契约：窗口历史逐条、按序、原样回灌；本轮输入紧随其后。
            messages.addAll(windowMessages);
            messages.addAll(input);
            return new LlmRequest(messages, ToolDefs.ofAll(requestTools));
        };

        ToolCallDispatcher dispatcher = (call, state) -> {
            ToolResult result;
            try {
                ToolContext callContext = new ToolContext(
                        baseContext.caller(),
                        baseContext.permissions(),
                        baseContext.config(),
                        call.arguments(),
                        baseContext.identity(),
                        baseContext.resources());
                result = authorizer.execute(registry, call.name(), callContext);
            } catch (RuntimeException unexpected) {
                // 不把异常消息或堆栈回灌给模型；审计细节留给宿主日志/事件层。
                result = ToolResult.error("TOOL_EXECUTION_ERROR", "工具执行失败（内部错误已脱敏）");
            }
            invocations.add(new ToolInvocation(call.id(), call.name(), call.arguments(), result));
            replyToWindow(window, call, result);
        };

        StopPolicy stopPolicy = new StopPolicy() {
            @Override
            public Optional<StopSignal> check(LoopState state) {
                if (state.rounds() >= maxRounds) {
                    return Optional.of(new StopSignal(StopReason.TURN_LIMIT,
                            "达到最大 LLM 往返轮数 " + maxRounds));
                }
                if (state.elapsedMillis() >= timeBudget.toMillis()) {
                    return Optional.of(new StopSignal(StopReason.TIME_BUDGET,
                            "达到时间上限 " + timeBudget.toMillis() + "ms"));
                }
                return Optional.empty();
            }

            @Override
            public Optional<StopSignal> checkToolCallBoundary(LoopState state) {
                // 工具批内只检查硬超时/取消类边界；轮数只在下一个往返前统一判定。
                if (state.elapsedMillis() >= timeBudget.toMillis()) {
                    return Optional.of(new StopSignal(StopReason.TIME_BUDGET,
                            "工具分派时达到时间上限 " + timeBudget.toMillis() + "ms"));
                }
                return Optional.empty();
            }
        };

        TurnResult turnResult;
        synchronized (window) {
            AgentLoop loop = AgentLoop.builder()
                    .window(window)
                    .composer(composer)
                    .llm(llm)
                    .dispatcher(dispatcher)
                    .stopPolicy(stopPolicy)
                    .maxToolCallsPerResponse(maxToolCallsPerResponse)
                    .build();
            turnResult = loop.runTurn(turnInput, toolSnapshot);
        }
        return new AgentRunResult(turnResult, invocations);
    }

    private static void replyToWindow(ContextWindow window, ContentPart.ToolCall call,
                                      ToolResult result) {
        ContentPart.ToolResult part = result.success()
                ? new ContentPart.ToolResult(call.id(), call.name(), result.message(), null)
                : new ContentPart.ToolResult(call.id(), call.name(), null,
                        result.code() + ": " + result.message());
        window.append(LlmMessage.tool(part));
    }
}
