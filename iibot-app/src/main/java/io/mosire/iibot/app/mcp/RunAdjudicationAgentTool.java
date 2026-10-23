package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.agent.AdjudicationResult;
import io.mosire.iibot.taskflow.agent.AdjudicationService;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 外部 supervisor 调用的中央推演 Agent 工具。
 *
 * <p>本工具只负责受控地触发已由组合根装配好的 {@link AdjudicationService}：先做参数、
 * revision 与回合状态校验，再以构造期绑定的 supervisor actor 运行 LLM Agent。外部参数
 * 不能选择 actor、不能提交或确认玩家决策，也不能绕过 {@code ToolCallAuthorizer}。</p>
 *
 * <p>工具结果是脱敏摘要：不返回 LLM 原文、失败原因原文、prompt、API key 或异常堆栈。
 * 同一 {@code idempotencyKey} 会缓存第一次执行结果，重复调用不会再次运行 LLM。</p>
 */
public final class RunAdjudicationAgentTool implements AgentTool {

    public static final String NAME = "tf_run_adjudication_agent";

    private static final String NAMESPACE = "taskflow";
    private static final String SUPERVISOR_ROLE = "supervisor";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> ROLES = Set.of(SUPERVISOR_ROLE);
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(NAMESPACE, ResourcePolicy.DENY);
    private static final ToolSpec SPEC = ToolSpec.level(AccessToken.DEFAULT, true, true);
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "运行项目内置的中央推演 Agent；仅允许在 LOCKED 或 FAILED_RETRYABLE 回合调用。",
            "properties", Map.of(
                    "expectedRevision", Map.of(
                            "type", "integer",
                            "minimum", 0,
                            "description", "当前回合 revision；必须与调用时完全一致"),
                    "idempotencyKey", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "幂等键；同一键只执行一次 LLM 推演"),
                    "reason", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "调用原因摘要；不会进入 LLM prompt")),
            "required", List.of("expectedRevision", "idempotencyKey", "reason"),
            "additionalProperties", false);

    private final TaskflowEngine engine;
    private final AdjudicationService adjudicationService;
    private final Actor supervisor;
    private final Object cacheLock = new Object();
    private final Map<String, ToolResult> results = new LinkedHashMap<>();

    public RunAdjudicationAgentTool(TaskflowEngine engine,
                                    AdjudicationService adjudicationService,
                                    Actor supervisor) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.adjudicationService = Objects.requireNonNull(
                adjudicationService, "adjudicationService");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "运行项目内置的中央推演 Agent，并只在 Agent 通过唯一提交工具成功提交后返回摘要；"
                + "要求 expectedRevision、idempotencyKey、reason，不返回 LLM 原文。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Set<String> roles() {
        return ROLES;
    }

    @Override
    public ResourceManifest resources() {
        return RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            // 资源断言先于参数读取/领域调用；拒绝必须原样交给 ToolCallAuthorizer。
            context.resources().require(Operation.WRITE, ResourceId.of(NAMESPACE, NAME));
            Arguments arguments = Arguments.from(context.arguments());
            synchronized (cacheLock) {
                ToolResult cached = results.get(arguments.idempotencyKey());
                if (cached != null) {
                    return cached;
                }

                ToolViewResult validated = validateTurn(arguments.expectedRevision());
                if (validated.failure() != null) {
                    results.put(arguments.idempotencyKey(), validated.failure());
                    return validated.failure();
                }

                ToolResult result;
                try {
                    AdjudicationResult adjudication = adjudicationService.adjudicate(
                            supervisor, engine.allWorldFacts());
                    result = toToolResult(adjudication);
                } catch (RuntimeException failure) {
                    // 不把异常消息（可能含 provider/HTTP 细节）回显给外部 Agent。
                    result = failureResult("ADJUDICATION_FAILED",
                            "中央推演 Agent 执行失败，未提交任何伪造结果", Map.of());
                }
                results.put(arguments.idempotencyKey(), result);
                return result;
            }
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (InvalidArgumentsException invalid) {
            return failureResult("INVALID_ARGUMENTS", invalid.getMessage(), Map.of());
        } catch (RuntimeException unexpected) {
            return failureResult("INTERNAL_ERROR", "中央推演工具内部错误", Map.of());
        }
    }

    private ToolViewResult validateTurn(long expectedRevision) {
        TurnView turn = engine.turnView().orElse(null);
        if (turn == null) {
            return ToolViewResult.failure(failureResult("WRONG_STATE",
                    "当前没有进行中的回合", Map.of()));
        }
        if (turn.revision() != expectedRevision) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", turn.turnId());
            data.put("state", turn.state().name());
            data.put("expectedRevision", expectedRevision);
            data.put("actualRevision", turn.revision());
            return ToolViewResult.failure(failureResult("REVISION_CONFLICT",
                    "回合 revision 不匹配，请重新读取状态", data));
        }
        if (turn.state() != TurnState.LOCKED
                && turn.state() != TurnState.FAILED_RETRYABLE) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", turn.turnId());
            data.put("state", turn.state().name());
            return ToolViewResult.failure(failureResult("WRONG_STATE",
                    "回合状态不是 LOCKED 或 FAILED_RETRYABLE", data));
        }
        return ToolViewResult.ok();
    }

    private ToolResult toToolResult(AdjudicationResult adjudication) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", adjudication.committed());
        data.put("turnId", adjudication.turnId());
        data.put("state", adjudication.turnState().name());
        data.put("message", adjudication.committed()
                ? "中央推演已提交" : "中央推演未提交有效提案");
        data.put("toolCallCount", adjudication.toolInvocations().size());
        data.put("worldVersion", engine.status().worldVersion());
        data.put("engineRevision", engine.status().engineRevision());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", adjudication.committed());
        output.put("code", adjudication.committed() ? "OK" : "ADJUDICATION_FAILED");
        output.put("tool", NAME);
        output.put("success", adjudication.committed());
        output.put("turnId", adjudication.turnId());
        output.put("state", adjudication.turnState().name());
        output.put("message", adjudication.committed()
                ? "中央推演已提交" : "中央推演未提交有效提案");
        output.put("toolCallCount", adjudication.toolInvocations().size());
        output.put("data", data);
        String json = json(output);
        return adjudication.committed()
                ? ToolResult.ok(json)
                : ToolResult.error("ADJUDICATION_FAILED", json);
    }

    private static ToolResult failureResult(String code, String message,
                                            Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("tool", NAME);
        output.put("message", message);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.error(code, json(output));
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\",\"tool\":\"" + NAME
                    + "\",\"message\":\"结果序列化失败\"}";
        }
    }

    private record Arguments(long expectedRevision, String idempotencyKey, String reason) {
        static Arguments from(Map<String, Object> raw) {
            Map<String, Object> arguments = raw == null ? Map.of() : raw;
            Object revisionValue = arguments.get("expectedRevision");
            if (!(revisionValue instanceof Number number) || !isIntegral(number)) {
                throw new InvalidArgumentsException(
                        "参数 expectedRevision 必须是整数");
            }
            long expectedRevision = number.longValue();
            if (expectedRevision < 0) {
                throw new InvalidArgumentsException(
                        "参数 expectedRevision 不能为负");
            }
            return new Arguments(expectedRevision,
                    requiredText(arguments, "idempotencyKey"),
                    requiredText(arguments, "reason"));
        }

        private static String requiredText(Map<String, Object> arguments, String name) {
            Object value = arguments.get(name);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
            }
            return text;
        }

        private static boolean isIntegral(Number number) {
            return number instanceof Byte || number instanceof Short
                    || number instanceof Integer || number instanceof Long
                    || number instanceof BigInteger;
        }
    }

    private record ToolViewResult(ToolResult failure) {
        static ToolViewResult ok() {
            return new ToolViewResult(null);
        }

        static ToolViewResult failure(ToolResult failure) {
            return new ToolViewResult(Objects.requireNonNull(failure, "failure"));
        }
    }

    private static final class InvalidArgumentsException extends RuntimeException {
        private InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
