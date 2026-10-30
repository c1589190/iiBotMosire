package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** B 站写工具的本地参数/结果层；不依赖 bot 模块的包私有 MCP 辅助类。 */
final class VideoToolSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private VideoToolSupport() {
    }

    static ToolResult ok(String tool, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("ok", true);
        body.put("data", data);
        return ToolResult.ok(json(body));
    }

    static ToolResult failure(String code, String message) {
        return ToolResult.error(code, message);
    }

    static String requiredText(Map<String, Object> args, String name, int maxChars) {
        Object value = args == null ? null : args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        return normalized;
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("B 站工具结果序列化失败", failure);
        }
    }

    static final class InvalidArgumentsException extends RuntimeException {
        InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
