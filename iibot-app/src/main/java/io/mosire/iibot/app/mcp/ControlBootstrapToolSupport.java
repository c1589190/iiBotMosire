package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.control.ControlOperationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 控制初始化工具共用的 schema 校验、授权断言与稳定 JSON envelope。 */
final class ControlBootstrapToolSupport {

    static final Set<String> ROLES = Set.of("supervisor");
    static final String NAMESPACE = "taskflow";
    static final ResourceManifest RESOURCES = ResourceManifest.of(NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId CONTROL_RESOURCE = ResourceId.of(NAMESPACE, "control-bootstrap");

    static final int MAX_ID_CHARS = 128;
    static final int MAX_QQ_CHARS = 64;
    static final int MAX_DISPLAY_NAME_CHARS = 128;
    static final int MAX_GROUP_CHARS = 128;
    static final int MAX_FACT_ID_CHARS = 128;
    static final int MAX_CONTENT_CHARS = 65_536;
    static final int MAX_VISIBILITY_CHARS = 1_024;
    static final int MAX_KEY_CHARS = 200;
    static final int MAX_REASON_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ControlBootstrapToolSupport() {
    }

    static ToolSpec writeSpec() {
        return ToolSpec.level(io.mosire.agentlib.permission.AccessToken.DEFAULT, true, false);
    }

    static ToolResult execute(String toolName, ToolContext context,
                              ControlAction action) {
        try {
            context.resources().require(Operation.WRITE, CONTROL_RESOURCE);
            return envelope(toolName, action.run());
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (InvalidArgumentsException invalid) {
            return failure(toolName, "INVALID_ARGUMENTS", invalid.getMessage(), Map.of());
        } catch (IllegalArgumentException invalid) {
            return failure(toolName, "INVALID_ARGUMENTS",
                    invalid.getMessage() == null ? "参数非法" : invalid.getMessage(), Map.of());
        } catch (RuntimeException failure) {
            return failure(toolName, "INTERNAL_ERROR", "工具执行失败（内部错误已脱敏）",
                    Map.of());
        }
    }

    @FunctionalInterface
    interface ControlAction {
        ControlOperationResult run();
    }

    static ToolResult envelope(String toolName, ControlOperationResult result) {
        if (result == null) {
            return failure(toolName, "INTERNAL_ERROR", "控制服务返回空结果", Map.of());
        }
        if (result.ok()) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", true);
            output.put("code", "OK");
            output.put("tool", toolName);
            output.put("data", result.data());
            return ToolResult.ok(json(output));
        }
        return failure(toolName, result.code(), result.message(), result.data());
    }

    static ToolResult failure(String toolName, String code, String message,
                              Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code == null ? "INTERNAL_ERROR" : code);
        output.put("tool", toolName);
        output.put("message", message == null ? "" : message);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.error(output.get("code").toString(), json(output));
    }

    static String requiredText(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgumentsException("参数 " + name + " 含控制字符");
        }
        return text;
    }

    /**
     * 正文参数校验：允许换行/回车/制表符，但拒绝 NUL 与其他 ISO control。
     * identifier/path 仍使用 {@link #requiredText} 的全控制字符拒绝规则。
     */
    static String requiredContent(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') {
                continue;
            }
            if (Character.isISOControl(character)) {
                throw new InvalidArgumentsException("参数 " + name + " 含非法控制字符");
            }
        }
        return text;
    }

    static String optionalText(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 提供时必须是字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgumentsException("参数 " + name + " 含控制字符");
        }
        return text;
    }

    static List<Map<String, Object>> requiredObjectArray(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空对象数组");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new InvalidArgumentsException("参数 " + name + " 的每一项必须是对象");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new InvalidArgumentsException("参数 " + name + " 的对象键必须是字符串");
                }
                normalized.put(key, entry.getValue());
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    static Map<String, Object> safeLedgerArguments(ToolContext context) {
        Map<String, Object> args = context == null ? null : context.arguments();
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("playerId", "qqId", "publicGroupId", "idempotencyKey",
                "reason", "visibility")) {
            if (args.containsKey(key)) {
                safe.put(key, args.get(key));
            }
        }
        Object facts = args.get("facts");
        if (facts instanceof List<?> list) {
            safe.put("factCount", list.size());
        }
        return Map.copyOf(safe);
    }

    static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\",\"data\":{}}";
        }
    }

    static final class InvalidArgumentsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidArgumentsException(String message) {
            super(message);
        }
    }

    /** 六个控制工具的共同骨架：supervisor-only、WRITE 资源断言、严格 schema。 */
    abstract static class BaseTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;

        BaseTool(String name, String description, Map<String, Object> schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public final String name() {
            return name;
        }

        @Override
        public final String description() {
            return description;
        }

        @Override
        public final Map<String, Object> jsonSchema() {
            return schema;
        }

        @Override
        public final ToolSpec spec() {
            return writeSpec();
        }

        @Override
        public final Set<String> roles() {
            return ROLES;
        }

        @Override
        public final ResourceManifest resources() {
            return RESOURCES;
        }

        @Override
        public final ToolResult execute(ToolContext context) {
            return ControlBootstrapToolSupport.execute(name, context,
                    () -> runChecked(context.arguments() == null ? Map.of() : context.arguments()));
        }

        protected abstract ControlOperationResult runChecked(Map<String, Object> arguments);

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            return safeLedgerArguments(context);
        }
    }
}
