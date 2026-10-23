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
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** GM 投诉 MCP 工具共用的稳定结果、资源与严格参数解析。 */
final class GmComplaintToolSupport {

    static final Set<String> ROLES = Set.of("supervisor");
    static final ResourceManifest RESOURCES = ResourceManifest.of(
            ComplaintStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId COMPLAINT_RESOURCE = ResourceId.of(
            ComplaintStore.RESOURCE_NAMESPACE, ComplaintStore.RESOURCE_NAMESPACE);

    static final int MAX_COMPLAINT_ID_CHARS = 200;
    static final int MAX_IDEMPOTENCY_KEY_CHARS = ComplaintService.MAX_IDEMPOTENCY_KEY_LENGTH;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GmComplaintToolSupport() {
    }

    static void requireRead(ToolContext context) {
        context.resources().require(Operation.READ, COMPLAINT_RESOURCE);
    }

    static void requireWrite(ToolContext context) {
        context.resources().require(Operation.WRITE, COMPLAINT_RESOURCE);
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
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("tool", tool);
        output.put("message", message == null ? "" : message);
        output.put("data", Map.of());
        return ToolResult.error(code, json(output));
    }

    static ToolResult fromCommand(String tool, ComplaintCommandResult result,
                                  Map<String, Object> extraData) {
        if (result == null) {
            return failure(tool, "INTERNAL_ERROR", "投诉服务返回空回执");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (result.complaintId() != null) {
            data.put("complaintId", result.complaintId());
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

    /** 摘要不含 body/responseBody。 */
    static Map<String, Object> summary(Complaint complaint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("complaintId", complaint.complaintId());
        item.put("playerId", complaint.playerId());
        item.put("title", complaint.title());
        item.put("status", complaint.status().name());
        item.put("revision", complaint.revision());
        item.put("createdAt", instant(complaint.createdAt()));
        item.put("updatedAt", instant(complaint.updatedAt()));
        item.put("respondedBy", complaint.respondedBy());
        item.put("respondedAt", instant(complaint.respondedAt()));
        item.put("deliveryState", complaint.deliveryState() == null
                ? null : complaint.deliveryState().name());
        return item;
    }

    static Map<String, Object> full(Complaint complaint) {
        Map<String, Object> item = summary(complaint);
        item.put("body", complaint.body());
        item.put("responseBody", complaint.responseBody());
        return item;
    }

    static String mapDomainCode(String domainCode) {
        if (domainCode == null) {
            return "INTERNAL_ERROR";
        }
        return switch (domainCode) {
            case ComplaintService.INVALID_ARGUMENT -> "INVALID_ARGUMENTS";
            case ComplaintService.COMPLAINT_NOT_FOUND -> "COMPLAINT_NOT_FOUND";
            case ComplaintService.REVISION_CONFLICT -> "REVISION_CONFLICT";
            case ComplaintService.INVALID_STATUS -> "INVALID_STATUS";
            case ComplaintService.FORBIDDEN -> "FORBIDDEN";
            case ComplaintService.DELIVERY_STATE_CONFLICT -> "DELIVERY_STATE_CONFLICT";
            case ComplaintService.STORE_UNAVAILABLE -> "STORE_UNAVAILABLE";
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
