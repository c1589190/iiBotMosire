package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** GM 秘书工作区 MCP 工具共用的稳定参数、资源与结果格式。 */
final class GmSecretaryToolSupport {

    static final String SUPERVISOR_ROLE = "supervisor";
    static final Set<String> ROLES = Set.of(SUPERVISOR_ROLE);
    static final ResourceManifest RESOURCES = ResourceManifest.of(
            SecretaryWorkspaceStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId WORKSPACE_RESOURCE = ResourceId.of(
            SecretaryWorkspaceStore.RESOURCE_NAMESPACE, "workspace");

    static final int MAX_CHAT_MESSAGE_CHARS = 16 * 1024;
    static final int MAX_IDEMPOTENCY_KEY_CHARS = 256;
    static final int MAX_REASON_CHARS = 2 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GmSecretaryToolSupport() {
    }

    static void requireRead(ToolContext context) {
        context.resources().require(Operation.READ, WORKSPACE_RESOURCE);
    }

    static void requireWrite(ToolContext context) {
        context.resources().require(Operation.WRITE, WORKSPACE_RESOURCE);
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
        return failure(tool, code, message, Map.of());
    }

    static ToolResult failure(String tool, String code, String message,
                              Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("tool", tool);
        output.put("message", message == null ? "" : message);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.error(code, json(output));
    }

    static String requiredText(Map<String, Object> arguments, String name,
                               int maxChars) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        return text;
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
