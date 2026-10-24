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
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketReply;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketStore;
import io.mosire.iibot.taskflow.ticket.TicketType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GM Ticket MCP 工具共用的授权、严格参数解析、摘要投影与 envelope。 */
final class GmTicketToolSupport {

    static final Set<String> ROLES = Set.of("supervisor");
    static final ResourceManifest RESOURCES = ResourceManifest.of(
            TicketStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    static final ResourceId TICKET_RESOURCE = ResourceId.of(
            TicketStore.RESOURCE_NAMESPACE, TicketStore.RESOURCE_NAMESPACE);

    static final int MAX_ID_CHARS = 128;
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_BODY_CHARS = 16 * 1024;
    static final int MAX_REASON_CHARS = 2 * 1024;
    static final int MAX_KEY_CHARS = 200;
    static final int MAX_BATCH_ITEMS = 100;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;
    static final int RECENT_LIMIT = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GmTicketToolSupport() {
    }

    static ToolSpec readSpec() {
        return ToolSpec.DEFAULT;
    }

    static ToolSpec writeSpec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
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

    static ToolResult commandFailure(String tool, TicketCommandResult result) {
        return failure(tool, result.code(), result.message());
    }

    static Map<String, Object> commandData(TicketCommandResult result, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (result.ticketId() != null) {
            data.put("ticketId", result.ticketId());
        }
        data.put("status", status);
        data.put("revision", result.revision());
        return data;
    }

    static Map<String, Object> summary(Ticket ticket) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ticketId", ticket.ticketId());
        item.put("type", ticket.type().name());
        item.put("playerId", ticket.playerId());
        item.put("authorActorId", ticket.authorActorId());
        item.put("title", ticket.title());
        item.put("status", ticket.status().name());
        item.put("revision", ticket.revision());
        item.put("createdAt", ticket.createdAt().toString());
        item.put("updatedAt", ticket.updatedAt().toString());
        item.put("claimedBy", ticket.claimedBy());
        item.put("replyCount", ticket.replies().size());
        return item;
    }

    static Map<String, Object> detail(Ticket ticket) {
        Map<String, Object> item = summary(ticket);
        item.put("body", ticket.body());
        List<Map<String, Object>> replies = new ArrayList<>();
        for (TicketReply reply : ticket.replies()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("replyId", reply.replyId());
            view.put("byActorId", reply.byActorId());
            view.put("body", reply.body());
            view.put("at", reply.at().toString());
            replies.add(view);
        }
        item.put("replies", replies);
        item.put("closedBy", ticket.closedBy());
        item.put("closeReason", ticket.closeReason());
        return item;
    }

    static TicketType optionalType(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("type");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("type 提供时必须是非空字符串");
        }
        try {
            return TicketType.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidArgumentsException("未知工单类型");
        }
    }

    static TicketStatus optionalStatus(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("status");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("status 提供时必须是非空字符串");
        }
        try {
            return TicketStatus.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidArgumentsException("未知工单状态");
        }
    }

    static int optionalLimit(Map<String, Object> args, int defaultValue) {
        Object raw = args == null ? null : args.get("limit");
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number) || number.longValue() != number.doubleValue()) {
            throw new InvalidArgumentsException("limit 必须是整数");
        }
        long value = number.longValue();
        if (value <= 0 || value > MAX_LIMIT) {
            throw new InvalidArgumentsException("limit 必须在 1.." + MAX_LIMIT + " 范围内");
        }
        return (int) value;
    }

    static long requiredNonNegativeLong(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof Number number) || number.longValue() != number.doubleValue()) {
            throw new InvalidArgumentsException(name + " 必须是整数");
        }
        long value = number.longValue();
        if (value < 0) {
            throw new InvalidArgumentsException(name + " 不能为负");
        }
        return value;
    }

    static String requiredText(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException(name + " 必须是非空字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException(name + " 超过长度上限");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgumentsException(name + " 含控制字符");
        }
        return text;
    }

    /** 正文允许换行/回车/制表符，拒绝 NUL 与其他 ISO control。 */
    static String requiredContent(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException(name + " 必须是非空字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException(name + " 超过长度上限");
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') {
                continue;
            }
            if (Character.isISOControl(character)) {
                throw new InvalidArgumentsException(name + " 含非法控制字符");
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
            throw new InvalidArgumentsException(name + " 提供时必须是非空字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArgumentsException(name + " 超过长度上限");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgumentsException(name + " 含控制字符");
        }
        return text;
    }

    static List<Map<String, Object>> requiredObjectList(Map<String, Object> args,
                                                         String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidArgumentsException(name + " 必须是非空对象数组");
        }
        if (list.size() > MAX_BATCH_ITEMS) {
            throw new InvalidArgumentsException(name + " 条目过多");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new InvalidArgumentsException(name + " 的每一项必须是对象");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new InvalidArgumentsException(name + " 的对象键必须是字符串");
                }
                normalized.put(key, entry.getValue());
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    static Map<String, Object> safeWriteLedgerArgs(ToolContext context) {
        Map<String, Object> args = context == null ? null : context.arguments();
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("ticketId", "type", "status", "expectedRevision",
                "idempotencyKey", "reason", "limit")) {
            if (args.containsKey(key)) {
                safe.put(key, args.get(key));
            }
        }
        Object items = args.get("items");
        if (items instanceof List<?> list) {
            List<Map<String, Object>> safeItems = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> safeItem = new LinkedHashMap<>();
                    for (String key : List.of("ticketId", "expectedRevision")) {
                        if (map.containsKey(key)) {
                            safeItem.put(key, map.get(key));
                        }
                    }
                    safeItems.add(safeItem);
                }
            }
            safe.put("items", safeItems);
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

    abstract static class BaseTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final ToolSpec spec;

        BaseTool(String name, String description, Map<String, Object> schema,
                 Operation operation, ToolSpec spec) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.operation = operation;
            this.spec = spec;
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
            return spec;
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
            try {
                context.resources().require(operation, TICKET_RESOURCE);
                return run(context.arguments() == null ? Map.of() : context.arguments());
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (InvalidArgumentsException invalid) {
                return failure(name, "INVALID_ARGUMENTS", invalid.getMessage());
            } catch (IllegalArgumentException invalid) {
                return failure(name, "INVALID_ARGUMENTS",
                        invalid.getMessage() == null ? "Ticket 参数非法" : invalid.getMessage());
            } catch (RuntimeException failure) {
                return failure(name, "TICKET_UNAVAILABLE",
                        "工单服务暂不可用（内部错误已脱敏）");
            }
        }

        protected abstract ToolResult run(Map<String, Object> arguments);

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            return safeWriteLedgerArgs(context);
        }
    }
}
