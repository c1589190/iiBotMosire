package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocs;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocsException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GM 私有文档 MCP 工具共用的授权、严格参数解析与稳定 envelope。 */
final class GmPrivateDocsToolSupport {

    static final Set<String> ROLES = Set.of("supervisor");
    static final ResourceManifest RESOURCES = ResourceManifest.of(
            GmPrivateDocs.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId DOCS_RESOURCE = ResourceId.of(
            GmPrivateDocs.RESOURCE_NAMESPACE, GmPrivateDocs.RESOURCE_NAMESPACE);

    static final int MAX_PATH_CHARS = 512;
    static final int MAX_IDEMPOTENCY_KEY_CHARS = 200;
    static final int MAX_REASON_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GmPrivateDocsToolSupport() {
    }

    static ToolSpec readSpec() {
        return ToolSpec.DEFAULT;
    }

    static ToolSpec writeSpec() {
        return ToolSpec.level(io.mosire.agentlib.permission.AccessToken.DEFAULT, true, false);
    }

    static void requireRead(ToolContext context) {
        context.resources().require(Operation.READ, DOCS_RESOURCE);
    }

    static void requireWrite(ToolContext context) {
        context.resources().require(Operation.WRITE, DOCS_RESOURCE);
    }

    static String requiredText(Map<String, Object> arguments, String name, int maxChars) {
        Object raw = arguments == null ? null : arguments.get(name);
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
     * path/identifier 参数继续走 {@link #requiredText} 的全控制字符拒绝规则。
     */
    static String requiredContent(Map<String, Object> arguments, String name, int maxChars) {
        Object raw = arguments == null ? null : arguments.get(name);
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
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgumentsException("参数 " + name + " 含控制字符");
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
        return failure(tool, code, message, Map.of());
    }

    static ToolResult failure(String tool, String code, String message,
                              Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code == null ? "INTERNAL_ERROR" : code);
        output.put("tool", tool);
        output.put("message", message == null ? "" : message);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.error(output.get("code").toString(), json(output));
    }

    static ToolResult docsFailure(String tool, GmPrivateDocsException failure) {
        return failure(tool, failure.code(), failure.getMessage());
    }

    static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\",\"data\":{}}";
        }
    }

    static Map<String, Object> writeData(GmPrivateDocs.WriteResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", result.path());
        data.put("status", result.status());
        data.put("contentHash", result.contentHash());
        data.put("size", result.size());
        return data;
    }

    static Map<String, Object> safeWriteLedgerArgs(ToolContext context) {
        Map<String, Object> args = context == null ? null : context.arguments();
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("path", "replace", "idempotencyKey", "reason")) {
            if (args.containsKey(key)) {
                safe.put(key, args.get(key));
            }
        }
        return Map.copyOf(safe);
    }

    static final class InvalidArgumentsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
