package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GM 推文 MCP 工具共用的稳定结果、资源与严格参数解析。 */
final class GmPostToolSupport {

    static final Set<String> ROLES = Set.of("supervisor");
    static final ResourceManifest RESOURCES = ResourceManifest.of(
            PostStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId POST_RESOURCE = ResourceId.of(
            PostStore.RESOURCE_NAMESPACE, PostStore.RESOURCE_NAMESPACE);

    static final int MAX_POST_ID_CHARS = 200;
    static final int MAX_IDEMPOTENCY_KEY_CHARS = PostService.MAX_IDEMPOTENCY_KEY_LENGTH;
    static final int MAX_REASON_CHARS = PostService.MAX_REJECTION_REASON_LENGTH;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GmPostToolSupport() {
    }

    static void requireRead(ToolContext context) {
        context.resources().require(Operation.READ, POST_RESOURCE);
    }

    static void requireWrite(ToolContext context) {
        context.resources().require(Operation.WRITE, POST_RESOURCE);
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

    static ToolResult fromCommand(String tool, PostCommandResult result,
                                  Map<String, Object> extraData) {
        if (result == null) {
            return failure(tool, "INTERNAL_ERROR", "推文服务返回空回执");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (result.postId() != null) {
            data.put("postId", result.postId());
        }
        data.put("revision", result.revision());
        if (extraData != null) {
            data.putAll(extraData);
        }
        if (result.ok()) {
            return success(tool, data);
        }
        String externalCode = mapDomainCode(result.code());
        data.put("domainCode", result.code());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", externalCode);
        output.put("tool", tool);
        output.put("message", result.message());
        output.put("data", data);
        return ToolResult.error(externalCode, json(output));
    }

    static Map<String, Object> summary(Post post) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("postId", post.postId());
        item.put("authorPlayerId", post.authorPlayerId());
        item.put("visibility", post.visibility().name());
        item.put("title", post.title());
        item.put("status", post.status().name());
        item.put("revision", post.revision());
        item.put("createdAt", instant(post.createdAt()));
        item.put("updatedAt", instant(post.updatedAt()));
        item.put("approvedBy", post.approvedBy());
        item.put("publishedBy", post.publishedBy());
        item.put("deliveryState", post.deliveryState() == null
                ? null : post.deliveryState().name());
        return item;
    }

    static Map<String, Object> full(Post post) {
        Map<String, Object> item = summary(post);
        item.put("body", post.body());
        return item;
    }

    static String mapDomainCode(String domainCode) {
        if (domainCode == null) {
            return "INTERNAL_ERROR";
        }
        return switch (domainCode) {
            case PostService.INVALID_ARGUMENT -> "INVALID_ARGUMENTS";
            case PostService.POST_NOT_FOUND -> "POST_NOT_FOUND";
            case PostService.REVISION_CONFLICT -> "REVISION_CONFLICT";
            case PostService.INVALID_STATUS -> "INVALID_STATUS";
            case PostService.FORBIDDEN -> "FORBIDDEN";
            case PostService.DELIVERY_STATE_CONFLICT -> "DELIVERY_STATE_CONFLICT";
            case PostService.STORE_UNAVAILABLE -> "STORE_UNAVAILABLE";
            default -> domainCode;
        };
    }

    static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\"}";
        }
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long
                || number instanceof java.math.BigInteger;
    }

    static final class InvalidArgumentsException extends RuntimeException {
        InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
