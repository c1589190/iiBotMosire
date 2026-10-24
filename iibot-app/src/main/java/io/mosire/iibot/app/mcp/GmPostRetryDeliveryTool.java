package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.delivery.PostDistributor;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 对 FAILED 推文执行一次显式送达重试；不直接调用 QQ。 */
public final class GmPostRetryDeliveryTool implements AgentTool {

    public static final String NAME = "tf_post_retry_delivery";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "把一条 FAILED 的已发布推文重新排队；afterMutation 再尝试一次外发。",
            "properties", Map.of(
                    "postId", Map.of("type", "string", "description", "推文 ID"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 1,
                            "description", "当前 revision；变化时拒绝")),
            "required", List.of("postId", "expectedRevision"),
            "additionalProperties", false);

    private final PostService postService;
    private final PostDistributor distributor;

    public GmPostRetryDeliveryTool(PostService postService, PostDistributor distributor) {
        this.postService = Objects.requireNonNull(postService, "postService");
        this.distributor = Objects.requireNonNull(distributor, "distributor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "GM 显式重试 FAILED 推文送达；只重置领域送达状态，实际发送由宿主分发器执行。";
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
            PostCommandResult result = distributor.retryFailedDelivery(postId, expectedRevision);
            Map<String, Object> extra = new LinkedHashMap<>();
            postService.post(postId).ifPresent(post -> {
                extra.put("status", post.status().name());
                extra.put("deliveryState", post.deliveryState() == null
                        ? null : post.deliveryState().name());
                extra.put("revision", post.revision());
            });
            return GmPostToolSupport.fromCommand(NAME, result, extra);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPostToolSupport.InvalidArgumentsException invalid) {
            return GmPostToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmPostToolSupport.failure(NAME, "POST_RETRY_UNAVAILABLE",
                    "推文送达重试失败（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String key : List.of("postId", "expectedRevision")) {
                if (args.containsKey(key)) {
                    safe.put(key, args.get(key));
                }
            }
        }
        return Map.copyOf(safe);
    }
}
