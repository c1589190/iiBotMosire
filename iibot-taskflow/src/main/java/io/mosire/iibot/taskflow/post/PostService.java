package io.mosire.iibot.taskflow.post;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 公共/私人推文的线程安全领域服务。
 *
 * <p>状态机：{@code PENDING_APPROVAL → APPROVED → PUBLISHED}，待审也可进入
 * {@code REJECTED}。秘书玩家只能创建自己的草稿；GM/DIRECTOR/SUPERVISOR 才能审核与发布。
 * 玩家和秘书都没有 approve/publish 能力。</p>
 *
 * <p><b>revision 定义</b>：每篇 {@link Post#revision()} 从 1 开始，审核、拒绝、发布和送达
 * 状态变更时递增。{@link #createDraft} 的 {@code expectedRevision} 是作者玩家的“草稿集合
 * revision”，从 0 开始；创建成功及该玩家推文被审核/拒绝/发布时都会递增。当前值由
 * {@link #collectionRevision(String)} 读取，列表工具也会返回它。审核/拒绝/发布命令的
 * {@code expectedRevision} 则是目标推文自身的 revision。</p>
 *
 * <p><b>原子性</b>：所有写命令先在旧快照上构造完整新快照，调用 {@link PostStore#save}
 * 成功后才替换内存状态。校验失败、权限失败、状态/版本冲突或 store 失败都保持内存与
 * 存储不变；失败不缓存幂等回执，因此不会形成“失败也已落盘”的副作用。成功命令的幂等
 * 回执随快照持久化，重复键返回首次成功回执。</p>
 */
public final class PostService {

    public static final String OK = "OK";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";
    public static final String POST_NOT_FOUND = "POST_NOT_FOUND";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String POST_NOT_PUBLISHED = "POST_NOT_PUBLISHED";
    public static final String DELIVERY_STATE_CONFLICT = "DELIVERY_STATE_CONFLICT";
    public static final String STORE_UNAVAILABLE = "STORE_UNAVAILABLE";

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 10_000;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    public static final int MAX_REJECTION_REASON_LENGTH = 500;

    private static final Comparator<Post> POST_ORDER =
            Comparator.comparing(Post::createdAt).thenComparing(Post::postId);

    private final PostStore store;
    private final Clock clock;
    private PostSnapshot state;

    public PostService(PostStore store) {
        this(store, Clock.systemUTC());
    }

    public PostService(PostStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        PostSnapshot loaded = store.load();
        if (loaded == null) {
            throw new IllegalStateException("PostStore.load() 不能返回 null");
        }
        this.state = loaded;
    }

    /**
     * 创建当前秘书玩家自己的推文草稿，并直接进入 {@link PostStatus#PENDING_APPROVAL}。
     *
     * @param secretaryPlayer 必须是由宿主认证、角色为 PLAYER 且 actorId 即作者的玩家
     * @param expectedRevision 当前玩家草稿集合 revision
     */
    public synchronized PostCommandResult createDraft(Actor secretaryPlayer, String title, String body,
                                                       PostVisibility visibility,
                                                       long expectedRevision,
                                                       String idempotencyKey) {
        PostCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        PostCommandResult duplicate = state.idempotentResults().get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (secretaryPlayer == null || secretaryPlayer.role() != ActorRole.PLAYER) {
            return failure(FORBIDDEN, "只有玩家秘书可以创建自己的推文草稿", null, 0);
        }
        if (expectedRevision < 0) {
            return failure(INVALID_ARGUMENT, "expectedRevision 不能为负", null, 0);
        }
        if (title == null || title.isBlank()) {
            return failure(INVALID_ARGUMENT, "title 不能为空白", null, 0);
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return failure(INVALID_ARGUMENT, "title 超过长度限制", null, 0);
        }
        if (body == null || body.isBlank()) {
            return failure(INVALID_ARGUMENT, "body 不能为空白", null, 0);
        }
        if (body.length() > MAX_BODY_LENGTH) {
            return failure(INVALID_ARGUMENT, "body 超过长度限制", null, 0);
        }
        if (visibility == null) {
            return failure(INVALID_ARGUMENT, "visibility 不能为空", null, 0);
        }

        long currentCollectionRevision = state.collectionRevision(secretaryPlayer.actorId());
        if (expectedRevision != currentCollectionRevision) {
            return failure(REVISION_CONFLICT, "玩家草稿集合版本已变化，请刷新后重试",
                    null, currentCollectionRevision);
        }

        Instant now = clock.instant();
        String postId = UUID.randomUUID().toString();
        Post post = new Post(postId, secretaryPlayer.actorId(), visibility, title, body,
                PostStatus.PENDING_APPROVAL, 1L, now, now, null, null, null);
        PostCommandResult result = PostCommandResult.success(postId, post.revision());

        PostSnapshot next = state
                .withPost(post)
                .withCollectionRevision(secretaryPlayer.actorId(), currentCollectionRevision + 1)
                .withIdempotentResult(idempotencyKey, result)
                .withAuditEntry(new PostAuditEntry(PostAuditAction.CREATE, postId,
                        secretaryPlayer.actorId(), null, PostStatus.PENDING_APPROVAL,
                        post.revision(), "visibility=" + visibility, now));
        return commit(next, result);
    }

    /** PENDING_APPROVAL → APPROVED；DIRECTOR/GM/SUPERVISOR 才可执行。 */
    public synchronized PostCommandResult approve(Actor gm, String postId, long expectedRevision,
                                                   String idempotencyKey) {
        return review(gm, postId, expectedRevision, idempotencyKey, PostAuditAction.APPROVE,
                PostStatus.APPROVED, null);
    }

    /** PENDING_APPROVAL → REJECTED；原因只写审计摘要，不写正文。 */
    public synchronized PostCommandResult reject(Actor gm, String postId, long expectedRevision,
                                                  String idempotencyKey, String reason) {
        PostCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        PostCommandResult duplicate = state.idempotentResults().get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (reason == null || reason.isBlank()) {
            return failure(INVALID_ARGUMENT, "拒绝原因不能为空白", null, 0);
        }
        if (reason.length() > MAX_REJECTION_REASON_LENGTH) {
            return failure(INVALID_ARGUMENT, "拒绝原因超过长度限制", null, 0);
        }
        return review(gm, postId, expectedRevision, idempotencyKey, PostAuditAction.REJECT,
                PostStatus.REJECTED, reason);
    }

    /** APPROVED → PUBLISHED；发布是 GM 侧最终动作，秘书/玩家不能调用。 */
    public synchronized PostCommandResult publish(Actor gm, String postId, long expectedRevision,
                                                   String idempotencyKey) {
        PostCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        PostCommandResult duplicate = state.idempotentResults().get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以发布推文", postId, 0);
        }
        if (postId == null || postId.isBlank()) {
            return failure(INVALID_ARGUMENT, "postId 不能为空", null, 0);
        }
        Post post = state.findPost(postId).orElse(null);
        if (post == null) {
            return failure(POST_NOT_FOUND, "推文不存在", postId, 0);
        }
        if (expectedRevision != post.revision()) {
            return failure(REVISION_CONFLICT, "推文版本已变化，请刷新后重试",
                    postId, post.revision());
        }
        if (post.status() != PostStatus.APPROVED) {
            return failure(INVALID_STATUS, "只有已批准推文可以发布", postId, post.revision());
        }

        Instant now = clock.instant();
        Post updated = new Post(post.postId(), post.authorPlayerId(), post.visibility(),
                post.title(), post.body(), PostStatus.PUBLISHED, post.revision() + 1,
                post.createdAt(), now, post.approvedBy(), gm.actorId(), DeliveryState.PENDING);
        PostCommandResult result = PostCommandResult.success(postId, updated.revision());
        long collectionRevision = state.collectionRevision(post.authorPlayerId()) + 1;
        PostSnapshot next = state
                .withPost(updated)
                .withCollectionRevision(post.authorPlayerId(), collectionRevision)
                .withIdempotentResult(idempotencyKey, result)
                .withAuditEntry(new PostAuditEntry(PostAuditAction.PUBLISH, postId, gm.actorId(),
                        PostStatus.APPROVED, PostStatus.PUBLISHED, updated.revision(),
                        "published", now));
        return commit(next, result);
    }

    /**
     * 记录外部送达结果。仅 PUBLISHED 推文可调用；UNKNOWN 不得重置为 PENDING。
     *
     * <p>重复标记同一状态是安全 no-op；状态实际变化时推进推文 revision 并持久化。是否允许
     * 自动重发由上层 outbox 决定，本服务不自动发送任何内容。</p>
     */
    public synchronized PostCommandResult markDelivered(String postId, DeliveryState deliveryState) {
        if (postId == null || postId.isBlank()) {
            return failure(INVALID_ARGUMENT, "postId 不能为空", null, 0);
        }
        if (deliveryState == null) {
            return failure(INVALID_ARGUMENT, "deliveryState 不能为空", postId, 0);
        }
        Post post = state.findPost(postId).orElse(null);
        if (post == null) {
            return failure(POST_NOT_FOUND, "推文不存在", postId, 0);
        }
        if (post.status() != PostStatus.PUBLISHED) {
            return failure(POST_NOT_PUBLISHED, "只有已发布推文可以记录送达状态",
                    postId, post.revision());
        }
        if (post.deliveryState() == DeliveryState.UNKNOWN && deliveryState == DeliveryState.PENDING) {
            return failure(DELIVERY_STATE_CONFLICT, "UNKNOWN 不能重置为 PENDING",
                    postId, post.revision());
        }
        if (post.deliveryState() == deliveryState) {
            return PostCommandResult.success(postId, post.revision());
        }

        Instant now = clock.instant();
        Post updated = new Post(post.postId(), post.authorPlayerId(), post.visibility(),
                post.title(), post.body(), post.status(), post.revision() + 1,
                post.createdAt(), now, post.approvedBy(), post.publishedBy(), deliveryState);
        PostCommandResult result = PostCommandResult.success(postId, updated.revision());
        PostSnapshot next = state
                .withPost(updated)
                .withAuditEntry(new PostAuditEntry(PostAuditAction.DELIVERY, postId,
                        post.publishedBy() == null ? post.authorPlayerId() : post.publishedBy(),
                        post.status(), post.status(), updated.revision(),
                        "delivery=" + deliveryState, now));
        return commit(next, result);
    }

    /** 玩家可见：已发布公共推文 + 自己的已发布私人推文。 */
    public synchronized List<Post> postsVisibleToPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return List.of();
        }
        return state.posts().stream()
                .filter(post -> post.status() == PostStatus.PUBLISHED)
                .filter(post -> post.visibility() == PostVisibility.PUBLIC
                        || post.authorPlayerId().equals(playerId))
                .sorted(POST_ORDER)
                .toList();
    }

    /**
     * 秘书可见：已发布公共推文 + 自己的已发布私人推文 + 自己创建的未发布草稿。
     * 绝不返回其他玩家的草稿或私人推文。
     */
    public synchronized List<Post> postsVisibleToSecretary(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return List.of();
        }
        return state.posts().stream()
                .filter(post -> secretariatCanSee(playerId, post))
                .sorted(POST_ORDER)
                .toList();
    }

    /** 秘书按 postId 读取可见推文；不可见与不存在由工具层统一映射为稳定错误。 */
    public synchronized Optional<Post> visiblePostForSecretary(String playerId, String postId) {
        if (playerId == null || playerId.isBlank() || postId == null || postId.isBlank()) {
            return Optional.empty();
        }
        return state.posts().stream()
                .filter(post -> post.postId().equals(postId))
                .filter(post -> secretariatCanSee(playerId, post))
                .findFirst();
    }

    /** GM 可见全部推文与审核记录。 */
    public synchronized List<Post> postsForGm() {
        return state.posts().stream().sorted(POST_ORDER).toList();
    }

    /** 只读待审队列。 */
    public synchronized List<Post> pendingForGm() {
        return state.posts().stream()
                .filter(post -> post.status() == PostStatus.PENDING_APPROVAL)
                .sorted(POST_ORDER)
                .toList();
    }

    /** 当前玩家草稿集合 revision；创建草稿时作为 expectedRevision。 */
    public synchronized long collectionRevision(String playerId) {
        return state.collectionRevision(playerId);
    }

    /** {@link #collectionRevision(String)} 的显式别名。 */
    public synchronized long draftCollectionRevision(String playerId) {
        return collectionRevision(playerId);
    }

    /** 单篇推文的只读快照；调用方必须自行做可见性判断。 */
    public synchronized Optional<Post> post(String postId) {
        return state.findPost(postId);
    }

    /** 只读审计摘要；不包含推文正文或标题。 */
    public synchronized List<PostAuditEntry> auditLog() {
        return state.auditLog();
    }

    /** 最近一次成功持久化的快照；供宿主只读观察/测试。 */
    public synchronized PostSnapshot snapshot() {
        return state;
    }

    private PostCommandResult review(Actor gm, String postId, long expectedRevision,
                                     String idempotencyKey, PostAuditAction action,
                                     PostStatus targetStatus, String summary) {
        PostCommandResult keyFailure = validateIdempotencyKey(idempotencyKey);
        if (keyFailure != null) {
            return keyFailure;
        }
        PostCommandResult duplicate = state.idempotentResults().get(idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        if (!isGmSide(gm)) {
            return failure(FORBIDDEN, "只有 GM/DIRECTOR/SUPERVISOR 可以审核推文", postId, 0);
        }
        if (postId == null || postId.isBlank()) {
            return failure(INVALID_ARGUMENT, "postId 不能为空", null, 0);
        }
        Post post = state.findPost(postId).orElse(null);
        if (post == null) {
            return failure(POST_NOT_FOUND, "推文不存在", postId, 0);
        }
        if (expectedRevision != post.revision()) {
            return failure(REVISION_CONFLICT, "推文版本已变化，请刷新后重试",
                    postId, post.revision());
        }
        if (post.status() != PostStatus.PENDING_APPROVAL) {
            return failure(INVALID_STATUS, "只有待审核推文可以审核", postId, post.revision());
        }

        Instant now = clock.instant();
        Post updated = new Post(post.postId(), post.authorPlayerId(), post.visibility(),
                post.title(), post.body(), targetStatus, post.revision() + 1,
                post.createdAt(), now,
                action == PostAuditAction.APPROVE ? gm.actorId() : post.approvedBy(),
                post.publishedBy(), post.deliveryState());
        PostCommandResult result = PostCommandResult.success(postId, updated.revision());
        long collectionRevision = state.collectionRevision(post.authorPlayerId()) + 1;
        String auditSummary = action == PostAuditAction.REJECT ? summary : "reviewed";
        PostSnapshot next = state
                .withPost(updated)
                .withCollectionRevision(post.authorPlayerId(), collectionRevision)
                .withIdempotentResult(idempotencyKey, result)
                .withAuditEntry(new PostAuditEntry(action, postId, gm.actorId(),
                        PostStatus.PENDING_APPROVAL, targetStatus, updated.revision(),
                        auditSummary, now));
        return commit(next, result);
    }

    private PostCommandResult commit(PostSnapshot next, PostCommandResult success) {
        try {
            store.save(next);
            state = next;
            return success;
        } catch (RuntimeException failure) {
            // 构造新快照 + 保存成功后才切换内存，因此这里无需回滚已提交状态。
            return failure(STORE_UNAVAILABLE, "推文存储暂不可用，操作未生效",
                    success.postId(), success.revision());
        }
    }

    private static boolean secretariatCanSee(String playerId, Post post) {
        if (post.status() == PostStatus.PUBLISHED) {
            return post.visibility() == PostVisibility.PUBLIC
                    || post.authorPlayerId().equals(playerId);
        }
        return post.authorPlayerId().equals(playerId);
    }

    private static boolean isGmSide(Actor actor) {
        if (actor == null) {
            return false;
        }
        return actor.role() == ActorRole.DIRECTOR
                || actor.role() == ActorRole.GM
                || actor.role() == ActorRole.SUPERVISOR;
    }

    private static PostCommandResult validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return failure(INVALID_ARGUMENT, "idempotencyKey 不能为空白", null, 0);
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return failure(INVALID_ARGUMENT, "idempotencyKey 超过长度限制", null, 0);
        }
        return null;
    }

    private static PostCommandResult failure(String code, String message,
                                              String postId, long revision) {
        return PostCommandResult.failure(code, message, postId, Math.max(0L, revision));
    }
}
