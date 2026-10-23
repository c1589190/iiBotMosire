package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** GM 只读推文摘要列表；绝不含正文，包含全部待审/已发布推文。 */
public final class GmPostListTool implements AgentTool {

    public static final String NAME = "tf_post_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "列出 GM 可见的全部推文摘要，可按 status 过滤；不返回 body。",
            "properties", Map.of(
                    "status", Map.of("type", "string",
                            "enum", List.of("DRAFT", "PENDING_APPROVAL", "APPROVED",
                                    "REJECTED", "PUBLISHED"),
                            "description", "可选状态过滤")),
            "required", List.of(),
            "additionalProperties", false);

    private final PostService postService;

    public GmPostListTool(PostService postService) {
        this.postService = Objects.requireNonNull(postService, "postService");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读列出 GM 可见的全部推文摘要（含待审），可按状态过滤；不返回正文。";
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
            String statusText = GmPostToolSupport.optionalText(
                    context.arguments(), "status", 64);
            PostStatus status = statusText.isBlank() ? null : parseStatus(statusText);
            List<Post> posts = postService.postsForGm().stream()
                    .filter(post -> status == null || post.status() == status)
                    .toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", posts.size());
            data.put("status", status == null ? null : status.name());
            data.put("posts", posts.stream().map(GmPostToolSupport::summary).toList());
            return GmPostToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPostToolSupport.InvalidArgumentsException invalid) {
            return GmPostToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmPostToolSupport.failure(NAME, "POST_UNAVAILABLE",
                    "推文列表读取失败（内部错误已脱敏）");
        }
    }

    private static PostStatus parseStatus(String value) {
        try {
            return PostStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new GmPostToolSupport.InvalidArgumentsException(
                    "status 必须是合法推文状态");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return Map.of();
    }
}
