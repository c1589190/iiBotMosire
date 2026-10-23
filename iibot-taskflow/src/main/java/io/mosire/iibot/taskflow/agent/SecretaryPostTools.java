package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.post.PostVisibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 玩家秘书的推文工具：只读列表、可见性读取与创建自己的待审草稿。
 *
 * <p>身份只从宿主注入的 {@link ToolContext#config()} 读取；三个工具的 JSON Schema 都没有
 * {@code playerId} 参数，也不存在 approve/publish 工具。所有工具声明 {@code secretary}
 * 角色与 {@code post} 资源面，实际执行仍经过
 * {@link io.mosire.agentlib.tool.ToolCallAuthorizer}。</p>
 *
 * <p>列表与创建回执只返回 postId/status/revision 等摘要，不返回正文。读取工具为了回答
 * 当前秘书可见内容会返回正文，但正文不进入领域审计摘要。</p>
 */
public final class SecretaryPostTools {

    public static final String LIST = "secretary_post_list_visible";
    public static final String READ = "secretary_post_read_visible";
    public static final String CREATE = "secretary_post_create";

    public static final String POST_NOT_VISIBLE = "POST_NOT_VISIBLE";
    public static final String POST_UNAVAILABLE = "POST_UNAVAILABLE";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            PostStore.RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE_ID = ResourceId.of(
            PostStore.RESOURCE_NAMESPACE, PostStore.RESOURCE_NAMESPACE);
    private static final int MAX_READ_RESULT_CHARS = 16 * 1024;
    private static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    private SecretaryPostTools() {
    }

    /** 三个工具；顺序稳定，便于会话装配与测试对账。 */
    public static List<AgentTool> all(PostService postService) {
        Objects.requireNonNull(postService, "postService");
        return List.of(list(postService), read(postService), create(postService));
    }

    public static AgentTool list(PostService postService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
        return new PostTool(
                LIST,
                "列出当前秘书可见的推文摘要；不返回正文，也不接受 playerId。",
                schema,
                Operation.READ,
                context -> {
                    BoundPlayer bound = requirePlayer(context);
                    List<Post> posts = postService.postsVisibleToSecretary(bound.playerId());
                    StringBuilder result = new StringBuilder("collectionRevision: ")
                            .append(postService.collectionRevision(bound.playerId()))
                            .append("\n共 ").append(posts.size()).append(" 篇：");
                    for (Post post : posts) {
                        result.append("\n- postId=").append(post.postId())
                                .append(" status=").append(post.status())
                                .append(" visibility=").append(post.visibility())
                                .append(" revision=").append(post.revision());
                    }
                    return ToolResult.ok(result.toString());
                });
    }

    public static AgentTool read(PostService postService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "postId", Map.of("type", "string", "description", "要读取的推文 ID")),
                "required", List.of("postId"),
                "additionalProperties", false);
        return new PostTool(
                READ,
                "读取当前秘书可见的一篇推文；不可见与不存在返回同一稳定错误。",
                schema,
                Operation.READ,
                context -> {
                    BoundPlayer bound = requirePlayer(context);
                    String postId = requireString(context.arguments(), "postId");
                    Optional<Post> found = postService.visiblePostForSecretary(bound.playerId(), postId);
                    if (found.isEmpty()) {
                        return ToolResult.error(POST_NOT_VISIBLE, "推文不存在或当前秘书无权访问");
                    }
                    Post post = found.get();
                    String body = post.body().length() > MAX_READ_RESULT_CHARS
                            ? post.body().substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                            : post.body();
                    return ToolResult.ok("postId: " + post.postId()
                            + "\nstatus: " + post.status()
                            + "\nvisibility: " + post.visibility()
                            + "\nrevision: " + post.revision()
                            + "\ntitle: " + post.title()
                            + "\nbody:\n" + body);
                });
    }

    public static AgentTool create(PostService postService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "推文标题"),
                        "body", Map.of("type", "string", "description", "推文正文"),
                        "visibility", Map.of("type", "string",
                                "enum", List.of("PUBLIC", "PRIVATE"),
                                "description", "PUBLIC 或 PRIVATE"),
                        "expectedRevision", Map.of("type", "integer", "minimum", 0,
                                "description", "当前玩家草稿集合 revision，由列表工具返回"),
                        "idempotencyKey", Map.of("type", "string",
                                "description", "幂等键；同一键重复调用返回首次成功回执")),
                "required", List.of("title", "body", "visibility",
                        "expectedRevision", "idempotencyKey"),
                "additionalProperties", false);
        return new PostTool(
                CREATE,
                "为当前秘书所属玩家创建一篇待 GM 审核的推文草稿；不能批准或发布。",
                schema,
                Operation.WRITE,
                context -> {
                    BoundPlayer bound = requirePlayer(context);
                    Map<String, Object> args = context.arguments();
                    String title = requireString(args, "title");
                    String body = requireString(args, "body");
                    PostVisibility visibility = parseVisibility(requireString(args, "visibility"));
                    long expectedRevision = requireLong(args, "expectedRevision");
                    String idempotencyKey = requireString(args, "idempotencyKey");
                    PostCommandResult result = postService.createDraft(bound.actor(), title, body,
                            visibility, expectedRevision, idempotencyKey);
                    if (!result.ok()) {
                        return ToolResult.error(result.code(), result.message());
                    }
                    return ToolResult.ok("推文草稿已创建并进入待审核状态。\npostId: "
                            + result.postId()
                            + "\nstatus: " + io.mosire.iibot.taskflow.post.PostStatus.PENDING_APPROVAL
                            + "\nrevision: " + result.revision());
                }) {
            @Override
            public Map<String, Object> ledgerArgs(ToolContext context) {
                // 正文不进入可观察的工具审计参数；工具回执也不包含正文。
                Map<String, Object> safe = new LinkedHashMap<>(context.arguments());
                safe.remove("body");
                return Map.copyOf(safe);
            }
        };
    }

    private static BoundPlayer requirePlayer(ToolContext context) {
        Objects.requireNonNull(context, "context");
        Object actorValue = context.config().get(SecretaryToolContext.ACTOR);
        Object playerIdValue = context.config().get(SecretaryToolContext.PLAYER_ID);
        if (!(actorValue instanceof Actor actor)) {
            throw new IllegalArgumentException("宿主未绑定合法 actor");
        }
        if (!(playerIdValue instanceof String playerId) || playerId.isBlank()) {
            throw new IllegalArgumentException("宿主未绑定合法 playerId");
        }
        if (actor.role() != ActorRole.PLAYER || !actor.actorId().equals(playerId)) {
            throw new IllegalArgumentException("绑定的 actor 与 playerId 不一致或不是玩家");
        }
        return new BoundPlayer(playerId, actor);
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return text;
    }

    private static long requireLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        long result = number.longValue();
        if (number instanceof Double || number instanceof Float) {
            double doubleValue = number.doubleValue();
            if (doubleValue != Math.rint(doubleValue)) {
                throw new IllegalArgumentException(key + " 必须是整数");
            }
        }
        return result;
    }

    private static PostVisibility parseVisibility(String value) {
        try {
            return PostVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("visibility 必须是 PUBLIC 或 PRIVATE");
        }
    }

    private record BoundPlayer(String playerId, Actor actor) {
        private BoundPlayer {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(actor, "actor");
        }
    }

    /** 统一工具壳：metadata + 资源断言 + 稳定错误映射。 */
    private static class PostTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Operation operation;
        private final java.util.function.Function<ToolContext, ToolResult> body;

        private PostTool(String name, String description, Map<String, Object> schema,
                         Operation operation,
                         java.util.function.Function<ToolContext, ToolResult> body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Map.copyOf(schema);
            this.operation = Objects.requireNonNull(operation, "operation");
            this.body = Objects.requireNonNull(body, "body");
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
                context.resources().require(operation, RESOURCE_ID);
                return body.apply(context);
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(INVALID_ARGUMENTS, invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error(POST_UNAVAILABLE, "推文服务暂不可用（内部错误已脱敏）");
            }
        }
    }
}
