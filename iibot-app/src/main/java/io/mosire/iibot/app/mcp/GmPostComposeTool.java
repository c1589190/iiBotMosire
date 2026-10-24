package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.publication.ExternalPostService;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.post.PostVisibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 外部控制方直接撰写推文；DRAFT 只建草稿，PUBLISH_NOW 在同一次 MCP 调用内发布。
 *
 * <p>不依赖 ActionBatch 或 app 内 LLM；仍走 PostService 状态机、正文守卫、幂等和审计。</p>
 */
public final class GmPostComposeTool implements AgentTool {

    public static final String NAME = "tf_post_compose";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "外部控制方自由撰写推文；DRAFT 只建待审草稿，PUBLISH_NOW 直接发布。",
            "properties", Map.of(
                    "mode", Map.of("type", "string", "enum", List.of("DRAFT", "PUBLISH_NOW"),
                            "description", "DRAFT 或 PUBLISH_NOW"),
                    "visibility", Map.of("type", "string",
                            "enum", List.of("PUBLIC", "PRIVATE"),
                            "description", "PUBLIC 发当前推演群，PRIVATE 发已注册玩家"),
                    "playerId", Map.of("type", "string",
                            "description", "PRIVATE 时必填的已注册玩家 ID"),
                    "title", Map.of("type", "string", "minLength", 1,
                            "description", "内部标题"),
                    "body", Map.of("type", "string", "minLength", 1,
                            "description", "玩家可见正文"),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1,
                            "description", "幂等键")),
            "required", List.of("mode", "visibility", "title", "body", "idempotencyKey"),
            "additionalProperties", false);

    private final ExternalPostService service;
    private final Actor supervisor;

    public GmPostComposeTool(ExternalPostService service, Actor supervisor) {
        this.service = Objects.requireNonNull(service, "service");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "外部控制方自由撰写推文；不依赖 app 内 Agent/批次，DRAFT 不外发，"
                + "PUBLISH_NOW 发布后由宿主投递。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public Set<String> roles() {
        return GmPostToolSupport.ROLES;
    }

    @Override
    public io.mosire.agentlib.permission.ResourceManifest resources() {
        return GmPostToolSupport.RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmPostToolSupport.requireWrite(context);
            String mode = GmPostToolSupport.requiredText(
                    context.arguments(), "mode", 32).toUpperCase();
            if (!"DRAFT".equals(mode) && !"PUBLISH_NOW".equals(mode)) {
                throw new GmPostToolSupport.InvalidArgumentsException(
                        "mode 只允许 DRAFT 或 PUBLISH_NOW");
            }
            String visibilityText = GmPostToolSupport.requiredText(
                    context.arguments(), "visibility", 16).toUpperCase();
            PostVisibility visibility = switch (visibilityText) {
                case "PUBLIC" -> PostVisibility.PUBLIC;
                case "PRIVATE" -> PostVisibility.PRIVATE;
                default -> throw new GmPostToolSupport.InvalidArgumentsException(
                        "visibility 只允许 PUBLIC 或 PRIVATE");
            };
            String playerId = GmPostToolSupport.optionalText(
                    context.arguments(), "playerId", 200);
            String title = GmPostToolSupport.requiredText(
                    context.arguments(), "title",
                    io.mosire.iibot.taskflow.post.PostService.MAX_TITLE_LENGTH);
            String body = GmPostToolSupport.requiredText(
                    context.arguments(), "body",
                    io.mosire.iibot.taskflow.post.PostService.MAX_BODY_LENGTH);
            String idempotencyKey = GmPostToolSupport.requiredText(
                    context.arguments(), "idempotencyKey",
                    io.mosire.iibot.taskflow.post.PostService.MAX_IDEMPOTENCY_KEY_LENGTH);
            ExternalPostService.Result result = service.compose(
                    supervisor, visibility, playerId, title, body, idempotencyKey,
                    "PUBLISH_NOW".equals(mode));
            Map<String, Object> data = GmPostToolSupport.summary(result.post());
            data.put("mode", mode);
            data.put("guardApplied", result.guardApplied());
            data.put("publishNow", result.publishNow());
            if (result.guardApplied()) {
                data.put("guardSanitizedBody", result.sanitizedBody());
            }
            return GmPostToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPostToolSupport.InvalidArgumentsException invalid) {
            return GmPostToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (ExternalPostService.ExternalPostException failure) {
            return GmPostToolSupport.failure(
                    NAME, failure.code(), failure.getMessage(), failure.data());
        } catch (RuntimeException failure) {
            return GmPostToolSupport.failure(NAME, "POST_UNAVAILABLE",
                    "外部发帖服务暂不可用（内部原因已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String name : List.of("mode", "visibility", "playerId", "idempotencyKey")) {
                if (args.containsKey(name)) {
                    safe.put(name, args.get(name));
                }
            }
        }
        return Map.copyOf(safe);
    }
}
