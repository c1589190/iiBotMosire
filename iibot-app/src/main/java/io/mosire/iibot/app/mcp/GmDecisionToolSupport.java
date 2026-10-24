package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.decision.DecisionPackages;
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
import io.mosire.iibot.taskflow.action.ActionPoolException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * GM 行动池/裁定包/批次 MCP 工具的共享壳。
 *
 * <p>统一承担 supervisor 角色、资源命名空间声明、读/写操作断言、稳定错误映射与 JSON
 * 回执。工具实现只负责参数解析和领域调用；任何异常消息都不得包含行动正文。</p>
 */
final class GmDecisionToolSupport {

    static final String SUPERVISOR_ROLE = "supervisor";
    static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    static final String TOOL_UNAVAILABLE = "DECISION_TOOL_UNAVAILABLE";

    static final String DECISION_PACKAGE_NAMESPACE = "decision-package";
    static final String ACTION_BATCH_NAMESPACE = "action-batch";

    static final int MAX_ID_CHARS = 200;
    static final int MAX_KEY_CHARS = 500;
    static final int MAX_TITLE_CHARS = 500;
    static final int MAX_NOTE_CHARS = 2_000;
    static final int MAX_IDS = 500;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GmDecisionToolSupport() {
    }

    static AgentTool readTool(String name, String description, Map<String, Object> schema,
                              String namespace,
                              Function<ToolContext, ToolResult> body) {
        return new DecisionTool(name, description, schema, Operation.READ,
                ToolSpec.DEFAULT, namespace, body);
    }

    static AgentTool writeTool(String name, String description, Map<String, Object> schema,
                               String namespace,
                               Function<ToolContext, ToolResult> body) {
        return new DecisionTool(name, description, schema, Operation.WRITE,
                ToolSpec.level(AccessToken.DEFAULT, true, true), namespace, body);
    }

    static ToolResult success(String tool, Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("code", "OK");
        output.put("tool", tool);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.ok(json(output));
    }

    static ToolResult failure(String tool, String code, String message,
                              Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("tool", tool);
        output.put("message", message);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.error(code, json(output));
    }

    static String requiredText(Map<String, Object> arguments, String name, int maxChars) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        return text;
    }

    static String optionalText(Map<String, Object> arguments, String name, int maxChars) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 提供时必须是字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        return text;
    }

    static long requiredPositiveLong(Map<String, Object> arguments, String name) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (!(raw instanceof Number number) || !isIntegral(number)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是整数");
        }
        long value = number.longValue();
        if (value <= 0) {
            throw new InvalidArgumentsException("参数 " + name + " 必须为正");
        }
        return value;
    }

    static int requiredNonNegativeInt(Map<String, Object> arguments, String name) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (!(raw instanceof Number number) || !isIntegral(number)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是整数");
        }
        int value = number.intValue();
        if (value < 0 || number.longValue() != value) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非负整数");
        }
        return value;
    }

    static boolean optionalBoolean(Map<String, Object> arguments, String name) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof Boolean value)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是布尔值");
        }
        return value;
    }

    static List<String> requiredStringList(Map<String, Object> arguments, String name) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空字符串数组");
        }
        if (values.size() > MAX_IDS) {
            throw new InvalidArgumentsException("参数 " + name + " 条目数超过上限");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new InvalidArgumentsException("参数 " + name + " 只能包含非空字符串");
            }
            if (text.length() > MAX_ID_CHARS) {
                throw new InvalidArgumentsException("参数 " + name + " 含超长条目");
            }
            result.add(text);
        }
        return result;
    }

    static Set<Integer> optionalIndexSet(Map<String, Object> arguments, String name) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是整数数组");
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Number number) || !isIntegral(number)) {
                throw new InvalidArgumentsException("参数 " + name + " 只能包含整数");
            }
            int index = number.intValue();
            if (index < 0 || number.longValue() != index) {
                throw new InvalidArgumentsException("参数 " + name + " 只能包含非负整数");
            }
            result.add(index);
        }
        return Set.copyOf(result);
    }

    static String parseVerdict(Map<String, Object> arguments) {
        String verdict = requiredText(arguments, "verdict", 20).trim().toLowerCase(
                java.util.Locale.ROOT);
        if (!verdict.equals("approve") && !verdict.equals("reject")) {
            throw new InvalidArgumentsException("参数 verdict 只能是 approve 或 reject");
        }
        return verdict;
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Byte || number instanceof Short || number instanceof Integer
                || number instanceof Long || number instanceof BigInteger;
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\","
                    + "\"message\":\"结果序列化失败\"}";
        }
    }

    /** 参数形状错误；只在工具内部使用。 */
    static final class InvalidArgumentsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidArgumentsException(String message) {
            super(message);
        }
    }

    private static final class DecisionTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final ToolSpec spec;
        private final Set<String> roles = Set.of(SUPERVISOR_ROLE);
        private final ResourceManifest manifest;
        private final ResourceId resourceId;
        private final Function<ToolContext, ToolResult> body;

        private DecisionTool(String name, String description, Map<String, Object> schema,
                             Operation operation, ToolSpec spec, String namespace,
                             Function<ToolContext, ToolResult> body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Map.copyOf(Objects.requireNonNull(schema, "schema"));
            this.operation = Objects.requireNonNull(operation, "operation");
            this.spec = Objects.requireNonNull(spec, "spec");
            this.manifest = ResourceManifest.of(namespace, ResourcePolicy.DENY);
            this.resourceId = ResourceId.of(namespace, namespace);
            this.body = Objects.requireNonNull(body, "body");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return schema;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public Set<String> roles() {
            return roles;
        }

        @Override
        public ResourceManifest resources() {
            return manifest;
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                context.resources().require(operation, resourceId);
                return body.apply(context);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (ActionPoolException failure) {
                return failure(name, failure.code(), failure.getMessage(), Map.of());
            } catch (io.mosire.iibot.taskflow.decision.ActionPackageException failure) {
                return failure(name, failure.code(), failure.getMessage(), Map.of());
            } catch (DecisionPackages.RejectionException failure) {
                return failure(name, failure.code(), failure.getMessage(), Map.of());
            } catch (InvalidArgumentsException invalid) {
                return failure(name, INVALID_ARGUMENTS, invalid.getMessage(), Map.of());
            } catch (RuntimeException unexpected) {
                return failure(name, TOOL_UNAVAILABLE,
                        "行动池/裁定包工具暂不可用（内部错误已脱敏）", Map.of());
            }
        }
    }
}
