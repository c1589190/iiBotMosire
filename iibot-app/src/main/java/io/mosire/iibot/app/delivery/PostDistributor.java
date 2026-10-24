package io.mosire.iibot.app.delivery;

import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.post.DeliveryState;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 宿主侧推文外发器：只消费 GM 已发布且送达状态待处理的推文。
 *
 * <p>发送目标严格由领域快照决定：{@link PostVisibility#PUBLIC} 只广播到配置的公共群，
 * {@link PostVisibility#PRIVATE} 只发送到作者玩家配置的 QQ 私聊。玩家秘书/LLM 没有调用
 * 本类的工具，MCP 的发布工具也只调用 {@link PostService#publish}，不能指定发送目标。</p>
 *
 * <p><b>送达语义</b>：SENT→SENT、FAILED_BEFORE_SEND→FAILED、UNKNOWN→UNKNOWN；
 * UNKNOWN 不自动重发。本进程 attempted 集合确保同一 postId 只尝试一次；标记异常只记录
 * 脱敏日志，绝不回滚已提交的 PUBLISHED 状态。未知 playerId 映射记录为 FAILED 且不崩溃。</p>
 */
public final class PostDistributor {

    private static final Logger LOG = LoggerFactory.getLogger(PostDistributor.class);

    private final PostService postService;
    private final OutboxSender outbox;
    private final Supplier<String> publicGroupId;
    private final Function<String, String> playerQqById;
    private final Set<String> attemptedPostIds = new LinkedHashSet<>();

    /** 兼容固定路由构造器；每个目标在构造时冻结，供旧测试/装配使用。 */
    public PostDistributor(PostService postService, OutboxSender outbox, String publicGroupId,
                           Map<String, String> playerQqById) {
        this(postService, outbox, constantPublicGroup(publicGroupId),
                mapLookup(Objects.requireNonNull(playerQqById, "playerQqById")));
    }

    /**
     * 动态路由构造器：公开发送前读取当前群，私密发送前读取作者玩家当前 QQ。
     * 运行期注册玩家无需重建本分发器。
     */
    public PostDistributor(PostService postService, OutboxSender outbox,
                           Supplier<String> publicGroupId,
                           Function<String, String> playerQqById) {
        this.postService = Objects.requireNonNull(postService, "postService");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.publicGroupId = Objects.requireNonNull(publicGroupId, "publicGroupId");
        this.playerQqById = Objects.requireNonNull(playerQqById, "playerQqById");
    }

    /**
     * 扫描并发送所有 GM 已 PUBLISHED 且 deliveryState 为 PENDING/null 的推文。
     *
     * @return 本次尝试产生的发送结果；没有待发送推文时为空列表
     */
    public synchronized List<SendOutcome> distributeIfReady() {
        List<Post> ready = postService.postsForGm().stream()
                .filter(post -> post.status() == PostStatus.PUBLISHED)
                .filter(post -> post.deliveryState() == null
                        || post.deliveryState() == DeliveryState.PENDING)
                .filter(post -> !attemptedPostIds.contains(post.postId()))
                .toList();
        if (ready.isEmpty()) {
            return List.of();
        }

        List<SendOutcome> outcomes = new ArrayList<>();
        for (Post post : ready) {
            attemptedPostIds.add(post.postId());
            outcomes.addAll(distributeOne(post));
        }
        return List.copyOf(outcomes);
    }

    /**
     * GM 明确要求重试一个 FAILED 的已发布推文：只把送达状态重置为 PENDING 并释放本进程
     * 的 attempted 标记；实际外发仍由宿主 afterMutation 的 distributeIfReady 执行。
     */
    public synchronized PostCommandResult retryFailedDelivery(String postId, long expectedRevision) {
        if (postId == null || postId.isBlank()) {
            return PostCommandResult.failure(PostService.INVALID_ARGUMENT,
                    "postId 不能为空", null, 0);
        }
        Post post = postService.post(postId).orElse(null);
        if (post == null) {
            return PostCommandResult.failure(PostService.POST_NOT_FOUND,
                    "推文不存在", postId, 0);
        }
        if (post.revision() != expectedRevision) {
            return PostCommandResult.failure(PostService.REVISION_CONFLICT,
                    "推文 revision 已变化", postId, post.revision());
        }
        if (post.status() != PostStatus.PUBLISHED) {
            return PostCommandResult.failure(PostService.POST_NOT_PUBLISHED,
                    "只有已发布推文可以重试送达", postId, post.revision());
        }
        if (post.deliveryState() != DeliveryState.FAILED) {
            return PostCommandResult.failure(PostService.DELIVERY_STATE_CONFLICT,
                    "只有 FAILED 推文可以重试送达", postId, post.revision());
        }
        PostCommandResult result = postService.markDelivered(postId, DeliveryState.PENDING);
        if (result != null && result.ok()) {
            attemptedPostIds.remove(postId);
        }
        return result;
    }

    private List<SendOutcome> distributeOne(Post post) {
        try {
            if (post.visibility() == PostVisibility.PUBLIC) {
                String groupId = publicGroupId.get();
                if (groupId == null || groupId.isBlank()) {
                    LOG.warn("推文缺少公开群路由 postId={}", post.postId());
                    recordWithoutSend(post, "UNKNOWN_PUBLIC_GROUP");
                    return List.of(new SendOutcome(0,
                            "post:" + post.postId() + ":public",
                            SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                            "UNKNOWN_PUBLIC_GROUP", "公开群路由未配置（未发送）"));
                }
                List<SendOutcome> sent = outbox.broadcastToPublicGroup(
                        groupId, List.of(post.body()),
                        "post:" + post.postId() + ":public");
                return record(post, sent, DeliveryState.SENT);
            }
            if (post.visibility() == PostVisibility.PRIVATE) {
                String qqId = playerQqById.apply(post.authorPlayerId());
                if (qqId == null || qqId.isBlank()) {
                    LOG.warn("推文缺少作者 QQ 映射 postId={} playerId={}",
                            post.postId(), post.authorPlayerId());
                    recordWithoutSend(post, "UNKNOWN_PLAYER");
                    return List.of(new SendOutcome(0,
                            "private:post:" + post.postId() + ":private:reply:0",
                            SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                            "UNKNOWN_PLAYER", "作者玩家没有配置 QQ 映射（未发送）"));
                }
                List<SendOutcome> sent = outbox.sendPrivateToUser(qqId, List.of(post.body()),
                        "post:" + post.postId() + ":private");
                return record(post, sent, DeliveryState.SENT);
            }
            // 枚举当前只有两种，防御性失败而不是猜测目标。
            recordWithoutSend(post, "UNSUPPORTED_VISIBILITY");
            return List.of(new SendOutcome(0, "post:" + post.postId() + ":unknown",
                    SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                    "UNSUPPORTED_VISIBILITY", "不支持的推文可见范围（未发送）"));
        } catch (RuntimeException failure) {
            // OutboxSender 已把常规发送异常收敛为三态；此处只处理发送调用本身异常。
            // 无法证明消息未发出，按 UNKNOWN 处理，禁止自动重发。
            LOG.warn("推文发送调用异常 postId={} visibility={} type={}",
                    post.postId(), post.visibility(), failure.getClass().getSimpleName());
            recordWithoutSend(post, "SEND_CALL_FAILED");
            return List.of(new SendOutcome(0, "post:" + post.postId() + ":unknown",
                    SendReceipt.State.UNKNOWN, List.of(), "SEND_CALL_FAILED",
                    "发送调用异常，状态不确定（禁止自动重发）"));
        }
    }

    private List<SendOutcome> record(Post post, List<SendOutcome> outcomes,
                                     DeliveryState expectedSent) {
        if (outcomes == null || outcomes.isEmpty()) {
            LOG.warn("推文发送未产生回执 postId={} visibility={}",
                    post.postId(), post.visibility());
            recordWithoutSend(post, "EMPTY_SEND_OUTCOME");
            return List.of(new SendOutcome(0, "post:" + post.postId() + ":unknown",
                    SendReceipt.State.UNKNOWN, List.of(), "EMPTY_SEND_OUTCOME",
                    "发送未返回回执，状态不确定（禁止自动重发）"));
        }
        SendOutcome outcome = outcomes.get(0);
        DeliveryState target = switch (outcome.state()) {
            case SENT -> expectedSent;
            case FAILED_BEFORE_SEND -> DeliveryState.FAILED;
            case UNKNOWN -> DeliveryState.UNKNOWN;
        };
        mark(post.postId(), target);
        if (outcome.unknown()) {
            LOG.error("推文进入 UNKNOWN，需人工处置 postId={} key={}",
                    post.postId(), outcome.idempotencyKey());
        }
        return outcomes;
    }

    private void recordWithoutSend(Post post, String errorCode) {
        // 明确未发送时才可落 FAILED；SEND_CALL_FAILED 等不确定路径由调用方单独标记 UNKNOWN。
        if ("UNKNOWN_PLAYER".equals(errorCode) || "UNSUPPORTED_VISIBILITY".equals(errorCode)
                || "UNKNOWN_PUBLIC_GROUP".equals(errorCode)) {
            mark(post.postId(), DeliveryState.FAILED);
        } else {
            mark(post.postId(), DeliveryState.UNKNOWN);
        }
    }

    private static Supplier<String> constantPublicGroup(String publicGroupId) {
        if (publicGroupId == null || publicGroupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        return () -> publicGroupId;
    }

    private static Function<String, String> mapLookup(Map<String, String> playerQqById) {
        Map<String, String> copy = Map.copyOf(playerQqById);
        return copy::get;
    }

    private void mark(String postId, DeliveryState state) {
        try {
            PostCommandResult result = postService.markDelivered(postId, state);
            if (!result.ok()) {
                LOG.warn("推文送达状态标记失败 postId={} state={} code={}",
                        postId, state, result.code());
            }
        } catch (RuntimeException failure) {
            // 标记失败不能撤销已提交发布，也不能把异常消息（可能含内部信息）写日志。
            LOG.warn("推文送达状态标记异常 postId={} state={} type={}",
                    postId, state, failure.getClass().getSimpleName());
        }
    }
}
