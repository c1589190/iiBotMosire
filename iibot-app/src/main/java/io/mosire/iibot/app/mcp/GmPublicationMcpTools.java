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
import io.mosire.iibot.app.publication.PublicationException;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.post.PostService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** GM 专用发布 MCP 工具；只创建待审核草稿，不发送 QQ。 */
public final class GmPublicationMcpTools {

    public static final String GENERATE = "tf_publication_generate";
    public static final String OPENING = "tf_publication_opening";
    public static final List<String> NAMES = List.of(GENERATE, OPENING);
    public static final String RESOURCE_NAMESPACE = "publication";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROLES = Set.of("supervisor");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE =
            ResourceId.of(RESOURCE_NAMESPACE, RESOURCE_NAMESPACE);

    private GmPublicationMcpTools() {
    }

    public static List<AgentTool> all(PublicationOrchestrator orchestrator, Actor supervisor) {
        Objects.requireNonNull(orchestrator, "orchestrator");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(new GenerateTool(orchestrator, supervisor),
                new OpeningTool(orchestrator, supervisor));
    }

    public static AgentTool generate(PublicationOrchestrator orchestrator, Actor supervisor) {
        return new GenerateTool(Objects.requireNonNull(orchestrator, "orchestrator"),
                Objects.requireNonNull(supervisor, "supervisor"));
    }

    public static AgentTool opening(PublicationOrchestrator orchestrator, Actor supervisor) {
        return new OpeningTool(Objects.requireNonNull(orchestrator, "orchestrator"),
                Objects.requireNonNull(supervisor, "supervisor"));
    }

    private abstract static class PublicationTool implements AgentTool {
        protected final PublicationOrchestrator orchestrator;
        protected final Actor supervisor;

        private PublicationTool(PublicationOrchestrator orchestrator, Actor supervisor) {
            this.orchestrator = orchestrator;
            this.supervisor = supervisor;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, true);
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
                context.resources().require(Operation.WRITE, RESOURCE);
                return invoke(context);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (PublicationException failure) {
                return failure(failure.code(), failure.getMessage(), Map.of());
            } catch (InvalidArgumentsException invalid) {
                return failure("INVALID_ARGUMENTS", invalid.getMessage(), Map.of());
            } catch (RuntimeException unexpected) {
                return failure("PUBLICATION_UNAVAILABLE",
                        "发布编排暂不可用（内部错误已脱敏）", Map.of());
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

        protected final ToolResult success(Map<String, Object> data) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", true);
            output.put("code", "OK");
            output.put("tool", name());
            output.put("data", data);
            return ToolResult.ok(json(output));
        }

        protected final ToolResult failure(String code, String message,
                                           Map<String, Object> data) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", false);
            output.put("code", code);
            output.put("tool", name());
            output.put("message", message == null ? "" : message);
            output.put("data", data == null ? Map.of() : data);
            return ToolResult.error(code, json(output));
        }

        protected static String json(Map<String, Object> value) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException impossible) {
                return "{\"ok\":false,\"code\":\"INTERNAL_ERROR\"}";
            }
        }
    }

    private static final class GenerateTool extends PublicationTool {
        private GenerateTool(PublicationOrchestrator orchestrator, Actor supervisor) {
            super(orchestrator, supervisor);
        }

        @Override
        public String name() {
            return GENERATE;
        }

        @Override
        public String description() {
            return "对已 COMMITTED 行动批次生成公共/私密发布草稿；"
                    + "只进入 PENDING_APPROVAL，GM 审核发布前绝不发送。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of(
                    "type", "object",
                    "description", "生成一个批次的发布草稿映射。",
                    "properties", Map.of(
                            "batchId", Map.of("type", "string", "minLength", 1),
                            "idempotencyKey", Map.of("type", "string", "minLength", 1)),
                    "required", List.of("batchId", "idempotencyKey"),
                    "additionalProperties", false);
        }

        @Override
        protected ToolResult invoke(ToolContext context) {
            String batchId = requiredText(context.arguments(), "batchId", 200);
            String idempotencyKey = requiredText(context.arguments(), "idempotencyKey", 200);
            PublicationRecord record = orchestrator.generate(
                    supervisor, batchId, idempotencyKey);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("publicationId", record.publicationId());
            data.put("turnId", record.turnId());
            data.put("publicPostId", record.publicPostId());
            data.put("privatePostIds", record.privatePostIds());
            return success(data);
        }

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (context.arguments() != null) {
                for (String key : List.of("batchId", "idempotencyKey")) {
                    if (context.arguments().containsKey(key)) {
                        safe.put(key, context.arguments().get(key));
                    }
                }
            }
            return Map.copyOf(safe);
        }
    }

    private static final class OpeningTool extends PublicationTool {
        private OpeningTool(PublicationOrchestrator orchestrator, Actor supervisor) {
            super(orchestrator, supervisor);
        }

        @Override
        public String name() {
            return OPENING;
        }

        @Override
        public String description() {
            return "在零玩家阶段创建独立的 PUBLIC 开场推文草稿；"
                    + "只进入 PENDING_APPROVAL，不创建回合、不发送 QQ。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of(
                    "type", "object",
                    "description", "创建一条待审核的公共开场推文。",
                    "properties", Map.of(
                            "title", Map.of("type", "string", "minLength", 1),
                            "body", Map.of("type", "string", "minLength", 1),
                            "idempotencyKey", Map.of("type", "string", "minLength", 1)),
                    "required", List.of("title", "body", "idempotencyKey"),
                    "additionalProperties", false);
        }

        @Override
        protected ToolResult invoke(ToolContext context) {
            String title = requiredText(
                    context.arguments(), "title", PostService.MAX_TITLE_LENGTH);
            String body = requiredText(
                    context.arguments(), "body", PostService.MAX_BODY_LENGTH);
            String idempotencyKey = requiredText(context.arguments(), "idempotencyKey",
                    PostService.MAX_IDEMPOTENCY_KEY_LENGTH);
            PublicationRecord record = orchestrator.createOpening(
                    supervisor, title, body, idempotencyKey);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("publicationId", record.publicationId());
            data.put("publicPostId", record.publicPostId());
            data.put("kind", record.kind().name());
            return success(data);
        }

        @Override
        public Map<String, Object> ledgerArgs(ToolContext context) {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (context.arguments() != null
                    && context.arguments().containsKey("idempotencyKey")) {
                safe.put("idempotencyKey", context.arguments().get("idempotencyKey"));
            }
            return Map.copyOf(safe);
        }
    }

    private static final class InvalidArgumentsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
