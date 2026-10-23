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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** GM 审批推文：APPROVE 或 REJECT（必须给原因），只调用领域 PostService。 */
public final class GmPostDecideTool implements AgentTool {

    public static final String NAME = "tf_post_decide";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "审核待审推文；APPROVE 调 approve，REJECT 必须提供 reason 并调 reject。",
            "properties", Map.of(
                    "postId", Map.of("type", "string", "description", "推文 ID"),
                    "decision", Map.of("type", "string", "enum", List.of("APPROVE", "REJECT"),
                            "description", "APPROVE 或 REJECT"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 1,
                            "description", "目标推文当前 revision"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键重复调用返回首次成功回执"),
                    "reason", Map.of("type", "string",
                            "description", "拒绝原因；REJECT 时必填")),
            "required", List.of("postId", "decision", "expectedRevision", "idempotencyKey"),
            "additionalProperties", false);

    private final PostService postService;
    private final Actor supervisor;

    public GmPostDecideTool(PostService postService, Actor supervisor) {
        this.postService = Objects.requireNonNull(postService, "postService");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "GM 审批一条待审推文；APPROVE 或 REJECT（拒绝必须提供原因）；不发送 QQ。";
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
            Map<String, Object> args = context.arguments();
            String postId = GmPostToolSupport.requiredText(
                    args, "postId", GmPostToolSupport.MAX_POST_ID_CHARS);
            String decision = GmPostToolSupport.requiredText(args, "decision", 32)
                    .trim().toUpperCase(Locale.ROOT);
            long expectedRevision = GmPostToolSupport.requiredPositiveLong(
                    args, "expectedRevision");
            String idempotencyKey = GmPostToolSupport.requiredText(
                    args, "idempotencyKey", GmPostToolSupport.MAX_IDEMPOTENCY_KEY_CHARS);
            PostCommandResult result;
            if ("APPROVE".equals(decision)) {
                result = postService.approve(supervisor, postId, expectedRevision, idempotencyKey);
            } else if ("REJECT".equals(decision)) {
                String reason = GmPostToolSupport.requiredText(
                        args, "reason", GmPostToolSupport.MAX_REASON_CHARS);
                result = postService.reject(supervisor, postId, expectedRevision,
                        idempotencyKey, reason);
            } else {
                throw new GmPostToolSupport.InvalidArgumentsException(
                        "decision 必须是 APPROVE 或 REJECT");
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("decision", decision);
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
                    "推文审批失败（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String key : List.of("postId", "decision", "expectedRevision",
                    "idempotencyKey")) {
                if (args.containsKey(key)) {
                    safe.put(key, args.get(key));
                }
            }
        }
        return Map.copyOf(safe);
    }
}
