package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 最终发布推文；发布动作本身只进入领域状态机，QQ 发送由宿主 PostDistributor 编排。 */
public final class GmPostPublishTool implements AgentTool {

    public static final String NAME = "tf_post_publish";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "发布已批准推文；只提交领域发布，不直接调用 QQ 发送工具。",
            "properties", Map.of(
                    "postId", Map.of("type", "string", "description", "推文 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 1,
                            "description", "目标推文当前 revision"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键重复调用返回首次成功回执")),
            "required", List.of("postId", "expectedRevision", "idempotencyKey"),
            "additionalProperties", false);

    private final PostService postService;
    private final Actor supervisor;

    public GmPostPublishTool(PostService postService, Actor supervisor) {
        this.postService = Objects.requireNonNull(postService, "postService");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "GM 发布一篇已批准推文；发布后由宿主唯一外发编排发送到公共群或玩家私聊。";
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
    public java.util.Set<String> roles() {
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
            String postId = GmPostToolSupport.requiredText(
                    context.arguments(), "postId", GmPostToolSupport.MAX_POST_ID_CHARS);
            long expectedRevision = GmPostToolSupport.requiredPositiveLong(
                    context.arguments(), "expectedRevision");
            String idempotencyKey = GmPostToolSupport.requiredText(
                    context.arguments(), "idempotencyKey",
                    GmPostToolSupport.MAX_IDEMPOTENCY_KEY_CHARS);
            PostCommandResult result = postService.publish(
                    supervisor, postId, expectedRevision, idempotencyKey);
            Map<String, Object> extra = new LinkedHashMap<>();
            postService.post(postId).ifPresent(post -> {
                extra.put("status", post.status().name());
                extra.put("visibility", post.visibility().name());
                extra.put("deliveryState", post.deliveryState() == null
                        ? null : post.deliveryState().name());
            });
            return GmPostToolSupport.fromCommand(NAME, result, extra);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPostToolSupport.InvalidArgumentsException invalid) {
            return GmPostToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmPostToolSupport.failure(NAME, "POST_UNAVAILABLE",
                    "推文发布失败（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String key : List.of("postId", "expectedRevision", "idempotencyKey")) {
                if (args.containsKey(key)) {
                    safe.put(key, args.get(key));
                }
            }
        }
        return Map.copyOf(safe);
    }
}
