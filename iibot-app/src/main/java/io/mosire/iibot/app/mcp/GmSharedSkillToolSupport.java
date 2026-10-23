package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** GM 共享 Skill MCP 工具共用的稳定结果、资源与严格参数解析。 */
final class GmSharedSkillToolSupport {

    static final Set<String> ROLES = Set.of("supervisor");
    static final ResourceManifest RESOURCES = ResourceManifest.of(
            SharedSkillStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId SHARED_SKILL_RESOURCE = ResourceId.of(
            SharedSkillStore.RESOURCE_NAMESPACE, SharedSkillStore.RESOURCE_NAMESPACE);

    static final int MAX_PATH_CHARS = 512;
    static final int MAX_IDEMPOTENCY_KEY_CHARS = 200;
    static final int MAX_REASON_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GmSharedSkillToolSupport() {
    }

    static void requireRead(ToolContext context) {
        context.resources().require(Operation.READ, SHARED_SKILL_RESOURCE);
    }

    static void requireWrite(ToolContext context) {
        context.resources().require(Operation.WRITE, SHARED_SKILL_RESOURCE);
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

    static boolean optionalBoolean(Map<String, Object> arguments, String name,
                                   boolean defaultValue) {
        Object raw = arguments == null ? null : arguments.get(name);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Boolean value)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是布尔值");
        }
        return value;
    }

    static ToolResult success(String tool, Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("code", "OK");
        output.put("tool", tool);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.ok(json(output));
    }

    static ToolResult failure(String tool, String code, String message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("tool", tool);
        output.put("message", message == null ? "" : message);
        output.put("data", Map.of());
        return ToolResult.error(code, json(output));
    }

    static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\"}";
        }
    }

    static final class InvalidArgumentsException extends RuntimeException {
        InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
