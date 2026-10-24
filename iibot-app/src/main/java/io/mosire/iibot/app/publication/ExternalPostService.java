package io.mosire.iibot.app.publication;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.agent.PlayerFacingTextGuard;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 外部控制方自由发帖服务。
 *
 * <p>正文完全由 MCP 调用方提供，不依赖 ActionBatch、PublicationOrchestrator 或任何
 * app 内 LLM。仍复用 PostService 的草稿、审核、发布状态机、幂等键和审计事件。</p>
 */
public final class ExternalPostService {

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String TARGET_NOT_FOUND = "TARGET_NOT_FOUND";
    public static final String POST_CREATE_FAILED = "POST_CREATE_FAILED";
    public static final String POST_APPROVE_FAILED = "POST_APPROVE_FAILED";
    public static final String POST_PUBLISH_FAILED = "POST_PUBLISH_FAILED";
    public static final String TEXT_GUARD_REJECTED = "TEXT_GUARD_REJECTED";

    private final PostService postService;
    private final Predicate<String> playerExists;

    public ExternalPostService(PostService postService, Predicate<String> playerExists) {
        this.postService = Objects.requireNonNull(postService, "postService");
        this.playerExists = Objects.requireNonNull(playerExists, "playerExists");
    }

    public record Result(Post post, boolean guardApplied, boolean publishNow,
                         String sanitizedBody, List<String> guardViolations) {
        public Result {
            Objects.requireNonNull(post, "post");
            sanitizedBody = sanitizedBody == null ? "" : sanitizedBody;
            guardViolations = guardViolations == null ? List.of() : List.copyOf(guardViolations);
        }
    }

    public Result compose(Actor supervisor, PostVisibility visibility, String playerId,
                          String title, String body, String idempotencyKey,
                          boolean publishNow) {
        requireSupervisor(supervisor);
        Objects.requireNonNull(visibility, "visibility");
        if (title == null || title.isBlank()
                || title.length() > PostService.MAX_TITLE_LENGTH) {
            throw new ExternalPostException(INVALID_ARGUMENTS, "title 为空或超过长度限制");
        }
        if (body == null || body.isBlank()) {
            throw new ExternalPostException(INVALID_ARGUMENTS, "body 不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > PostService.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ExternalPostException(INVALID_ARGUMENTS, "idempotencyKey 为空或超过长度限制");
        }

        String author;
        if (visibility == PostVisibility.PUBLIC) {
            author = PostService.SYSTEM_PUBLICATION_AUTHOR;
        } else {
            if (playerId == null || playerId.isBlank()) {
                throw new ExternalPostException(INVALID_ARGUMENTS,
                        "PRIVATE 发帖必须提供已注册 playerId");
            }
            if (!playerExists.test(playerId)) {
                throw new ExternalPostException(TARGET_NOT_FOUND,
                        "PRIVATE 目标玩家不存在");
            }
            author = playerId;
        }

        String guarded = visibility == PostVisibility.PUBLIC
                ? PlayerFacingTextGuard.normalizeForPost(body)
                : PlayerFacingTextGuard.normalizeForChat(body);
        boolean guardApplied = !guarded.equals(body);
        List<String> guardViolations = PlayerFacingTextGuard.rawBackstageLeaks(body);
        if (!guardViolations.isEmpty()) {
            Map<String, Object> guardData = new LinkedHashMap<>();
            guardData.put("guardApplied", true);
            guardData.put("guardViolations", guardViolations);
            guardData.put("guardSanitizedBody", guarded);
            guardData.put("hint", "正文含玩家不可见的后台字段/Markdown；"
                    + "未创建草稿，请清理后重试");
            throw new ExternalPostException(TEXT_GUARD_REJECTED,
                    "正文触发玩家可见文本守卫，已拒绝创建草稿", guardData);
        }
        String digest = Hashes.sha256("external-post:" + idempotencyKey);
        String createKey = "external-post:" + digest + ":create";
        String approveKey = "external-post:" + digest + ":approve";
        String publishKey = "external-post:" + digest + ":publish";

        PostCommandResult created = postService.createPublicationDraft(
                supervisor, author, visibility, title, guarded, createKey);
        String postId = requirePostId(created, POST_CREATE_FAILED, "创建草稿失败");
        Post post = current(postId);
        if (!publishNow) {
            return new Result(post, guardApplied, false, guarded, guardViolations);
        }

        // 外部发帖的三段键固定对应同一 post 的 revision 1→2→3。
        // 重试时即使当前已到 3，也必须用原 revision 重放 approve/publish，
        // 否则 PostService 会因 expectedRevision 变化报幂等键冲突。
        PostCommandResult approved = postService.approve(
                supervisor, postId, 1L, approveKey);
        requireOk(approved, POST_APPROVE_FAILED, "批准草稿失败");
        PostCommandResult published = postService.publish(
                supervisor, postId, 2L, publishKey);
        requireOk(published, POST_PUBLISH_FAILED, "发布推文失败");
        return new Result(current(postId), guardApplied, true, guarded, guardViolations);
    }

    private Post current(String postId) {
        return postService.post(postId).orElseThrow(() ->
                new ExternalPostException(POST_CREATE_FAILED, "推文创建后不可见"));
    }

    private static String requirePostId(PostCommandResult result, String code, String message) {
        requireOk(result, code, message);
        if (result.postId() == null || result.postId().isBlank()) {
            throw new ExternalPostException(code, "推文服务未返回 postId");
        }
        return result.postId();
    }

    private static void requireOk(PostCommandResult result, String code, String message) {
        if (result == null) {
            throw new ExternalPostException(code, "推文服务返回空回执");
        }
        if (!result.ok()) {
            throw new ExternalPostException(code, message + ": " + result.code());
        }
    }

    private static void requireSupervisor(Actor actor) {
        if (actor == null || (actor.role() != ActorRole.SUPERVISOR
                && actor.role() != ActorRole.GM && actor.role() != ActorRole.DIRECTOR)) {
            throw new ExternalPostException("FORBIDDEN", "只有 GM/DIRECTOR/SUPERVISOR 可以发帖");
        }
    }

    /** 稳定失败码；消息不含正文、token 或物理路径。 */
    public static final class ExternalPostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private final Map<String, Object> data;

        public ExternalPostException(String code, String message) {
            this(code, message, Map.of());
        }

        public ExternalPostException(String code, String message,
                                     Map<String, Object> data) {
            super(message);
            this.code = code;
            this.data = data == null ? Map.of() : Map.copyOf(data);
        }

        public String code() {
            return code;
        }

        public Map<String, Object> data() {
            return data;
        }
    }
}
