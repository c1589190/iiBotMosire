package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import io.mosire.iibot.app.records.RecordProjectionException;
import io.mosire.iibot.app.records.RecordQueryService;
import io.mosire.iibot.app.records.RecordSummary;
import io.mosire.iibot.app.records.TurnRecordFile;
import io.mosire.iibot.app.records.TurnRecordSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** supervisor/strict 的 Markdown 回合记录查询三工具；不暴露物理路径。 */
public final class GmRecordMcpTools {

    public static final String TURN_LIST = "tf_record_turn_list";
    public static final String TURN_GET = "tf_record_turn_get";
    public static final String QUERY = "tf_record_query";
    public static final List<String> NAMES = List.of(TURN_LIST, TURN_GET, QUERY);
    public static final String RESOURCE_NAMESPACE = "records";

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Set<String> ROLES = Set.of("supervisor");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE =
            ResourceId.of(RESOURCE_NAMESPACE, RESOURCE_NAMESPACE);
    private static final int MAX_ID_CHARS = 200;
    private static final int MAX_FILE_CHARS = 300;

    private GmRecordMcpTools() {
    }

    public static List<AgentTool> all(RecordQueryService queryService) {
        Objects.requireNonNull(queryService, "queryService");
        return List.of(turnList(queryService), turnGet(queryService), query(queryService));
    }

    public static AgentTool turnList(RecordQueryService queryService) {
        return new TurnListTool(Objects.requireNonNull(queryService, "queryService"));
    }

    public static AgentTool turnGet(RecordQueryService queryService) {
        return new TurnGetTool(Objects.requireNonNull(queryService, "queryService"));
    }

    public static AgentTool query(RecordQueryService queryService) {
        return new QueryTool(Objects.requireNonNull(queryService, "queryService"));
    }

    private static final class TurnListTool extends RecordTool {
        private TurnListTool(RecordQueryService queryService) {
            super(TURN_LIST, "列出 Markdown 回合记录摘要；不返回正文、物理路径或玩家私密内容。",
                    Map.of("type", "object",
                            "description", "无参数列出回合摘要。",
                            "properties", Map.of(),
                            "required", List.of(),
                            "additionalProperties", false), queryService);
        }

        @Override
        protected ToolResult invoke(ToolContext context) {
            List<TurnRecordSummary> turns = queryService.listTurns();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", turns.size());
            data.put("turns", turns.stream().map(GmRecordMcpTools::summary).toList());
            return success(name(), data);
        }
    }

    private static final class TurnGetTool extends RecordTool {
        private TurnGetTool(RecordQueryService queryService) {
            super(TURN_GET, "读取一个回合的允许 Markdown 文件；只返回安全相对 file，"
                            + "不返回物理路径。",
                    Map.of("type", "object",
                            "description", "按 turnId 与安全相对 file 读取 Markdown。",
                            "properties", Map.of(
                                    "turnId", Map.of("type", "string", "minLength", 1),
                                    "file", Map.of("type", "string", "minLength", 1)),
                            "required", List.of("turnId", "file"),
                            "additionalProperties", false), queryService);
        }

        @Override
        protected ToolResult invoke(ToolContext context) {
            String turnId = requiredText(context.arguments(), "turnId", MAX_ID_CHARS);
            String file = requiredText(context.arguments(), "file", MAX_FILE_CHARS);
            TurnRecordFile record = queryService.getTurn(turnId, file);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", record.turnId());
            data.put("file", record.file());
            data.put("contentHash", record.contentHash());
            data.put("content", record.content());
            return success(name(), data);
        }
    }

    private static final class QueryTool extends RecordTool {
        private QueryTool(RecordQueryService queryService) {
            super(QUERY, "按 kind/turnId/playerId/postId/batchId 过滤记录摘要；"
                            + "不返回正文或物理路径。",
                    Map.of("type", "object",
                            "description", "过滤记录摘要；所有参数可选。",
                            "properties", Map.of(
                                    "kind", Map.of("type", "string",
                                            "enum", List.of("turn", "public-narrative",
                                                    "player", "action", "package", "batch",
                                                    "post", "opening")),
                                    "turnId", Map.of("type", "string"),
                                    "playerId", Map.of("type", "string"),
                                    "postId", Map.of("type", "string"),
                                    "batchId", Map.of("type", "string")),
                            "required", List.of(),
                            "additionalProperties", false), queryService);
        }

        @Override
        protected ToolResult invoke(ToolContext context) {
            String kind = optionalText(context.arguments(), "kind", 64);
            String turnId = optionalText(context.arguments(), "turnId", MAX_ID_CHARS);
            String playerId = optionalText(context.arguments(), "playerId", MAX_ID_CHARS);
            String postId = optionalText(context.arguments(), "postId", MAX_ID_CHARS);
            String batchId = optionalText(context.arguments(), "batchId", MAX_ID_CHARS);
            List<RecordSummary> records = queryService.query(kind, turnId, playerId,
                    postId, batchId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", records.size());
            data.put("records", records.stream().map(GmRecordMcpTools::summary).toList());
            return success(name(), data);
        }
    }

    private abstract static class RecordTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        protected final RecordQueryService queryService;

        private RecordTool(String name, String description, Map<String, Object> schema,
                           RecordQueryService queryService) {
            this.name = name;
            this.description = description;
            this.schema = Map.copyOf(schema);
            this.queryService = queryService;
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
            return ToolSpec.DEFAULT;
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
                context.resources().require(Operation.READ, RESOURCE);
                return invoke(context);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (RecordProjectionException failure) {
                return failure(name, failure.code(), failure.getMessage(), Map.of());
            } catch (InvalidArgumentsException invalid) {
                return failure(name, RecordProjectionException.INVALID_ARGUMENTS,
                        invalid.getMessage(), Map.of());
            } catch (RuntimeException unexpected) {
                return failure(name, "RECORD_UNAVAILABLE",
                        "记录查询暂不可用（内部错误已脱敏）", Map.of());
            }
        }

        protected abstract ToolResult invoke(ToolContext context);

        protected static String requiredText(Map<String, Object> arguments, String name,
                                             int maxChars) {
            Object raw = arguments == null ? null : arguments.get(name);
            if (!(raw instanceof String text) || text.isBlank()) {
                throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
            }
            if (text.length() > maxChars) {
                throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
            }
            return text;
        }

        protected static String optionalText(Map<String, Object> arguments, String name,
                                             int maxChars) {
            Object raw = arguments == null ? null : arguments.get(name);
            if (raw == null) {
                return null;
            }
            if (!(raw instanceof String text) || text.isBlank()) {
                throw new InvalidArgumentsException("参数 " + name + " 提供时必须是字符串");
            }
            if (text.length() > maxChars) {
                throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
            }
            return text;
        }

        protected static ToolResult success(String tool, Map<String, Object> data) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", true);
            output.put("code", "OK");
            output.put("tool", tool);
            output.put("data", data == null ? Map.of() : data);
            return ToolResult.ok(json(output));
        }

        protected static ToolResult failure(String tool, String code, String message,
                                            Map<String, Object> data) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", false);
            output.put("code", code);
            output.put("tool", tool);
            output.put("message", message == null ? "" : message);
            output.put("data", data == null ? Map.of() : data);
            return ToolResult.error(code, json(output));
        }

        private static String json(Map<String, Object> value) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException impossible) {
                return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\"}";
            }
        }
    }

    private static Map<String, Object> summary(TurnRecordSummary turn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("turnNo", turn.turnNo());
        row.put("turnId", turn.turnId());
        row.put("status", turn.status());
        row.put("worldVersion", turn.worldVersion());
        row.put("openedAt", turn.openedAt());
        row.put("closedAt", turn.closedAt());
        row.put("batchIds", turn.batchIds());
        row.put("packageIds", turn.packageIds());
        row.put("postIds", turn.postIds());
        row.put("publicationIds", turn.publicationIds());
        row.put("batchCount", turn.batchIds().size());
        row.put("postCount", turn.postIds().size());
        return row;
    }

    private static Map<String, Object> summary(RecordSummary record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", record.kind());
        row.put("turnId", record.turnId());
        row.put("playerId", record.playerId());
        row.put("actionId", record.actionId());
        row.put("packageId", record.packageId());
        row.put("batchId", record.batchId());
        row.put("postId", record.postId());
        row.put("publicationId", record.publicationId());
        row.put("file", record.file());
        row.put("contentHash", record.contentHash());
        row.put("summary", record.summary());
        return row;
    }

    private static final class InvalidArgumentsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
