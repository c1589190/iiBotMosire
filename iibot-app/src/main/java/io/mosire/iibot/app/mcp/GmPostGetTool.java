package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 只读读取任意推文；只有本工具允许返回正文。 */
public final class GmPostGetTool implements AgentTool {

    public static final String NAME = "tf_post_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按 postId 读取 GM 可见的完整推文（含正文）；只读。",
            "properties", Map.of(
                    "postId", Map.of("type", "string", "description", "推文 ID")),
            "required", List.of("postId"),
            "additionalProperties", false);

    private final PostService postService;

    public GmPostGetTool(PostService postService) {
        this.postService = Objects.requireNonNull(postService, "postService");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读读取一篇推文的完整内容（含正文）；GM 可读取全部推文。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.DEFAULT;
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
            GmPostToolSupport.requireRead(context);
            String postId = GmPostToolSupport.requiredText(
                    context.arguments(), "postId", GmPostToolSupport.MAX_POST_ID_CHARS);
            Post post = postService.post(postId).orElse(null);
            if (post == null) {
                return GmPostToolSupport.failure(NAME, "POST_NOT_FOUND", "推文不存在");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("post", GmPostToolSupport.full(post));
            return GmPostToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPostToolSupport.InvalidArgumentsException invalid) {
            return GmPostToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmPostToolSupport.failure(NAME, "POST_UNAVAILABLE",
                    "推文读取失败（内部错误已脱敏）");
        }
    }
}
