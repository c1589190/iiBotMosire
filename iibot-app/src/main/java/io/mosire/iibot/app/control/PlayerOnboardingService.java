package io.mosire.iibot.app.control;

import io.mosire.iibot.app.delivery.DeliveryLedger;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.SendOutcome;
import io.mosire.iibot.app.mcp.ControlMcpConfig;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.agent.MappedIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 公屏自主入团：校验来源群消息、注册玩家、绑定秘书并发送报名卡。
 *
 * <p>这是 D-017 的受控例外：策略允许时不要求 app 内 Agent 审核或逐次真人确认；
 * 以来源消息 opt-in、群 allowlist、人数限制、单 QQ 幂等、delivery ledger 和审计替代。
 * 不发送群公告、不批量抓取、不向未报名用户主动私聊。</p>
 */
public final class PlayerOnboardingService {

    public static final String DEFAULT_REGISTRATION_FORM = """
            《七柱之下：败退之后》个人本可以开始了。

            你在群里说要加入。回我这几项就能开始：
            1. 姓名
            2. 性别
            3. 年龄
            4. 职位
            5. 其他介绍

            其他介绍可以写出身、性格、经历、目标、关系、秘密，或者你希望开局关注什么；一句话也可以。职位不必从固定列表里选，按你想要的来。
            """;

    private static final java.util.regex.Pattern OPT_IN = java.util.regex.Pattern.compile(
            "(?i)(报名|加入|参加|想玩|带我|申请入团|申请加入)");

    /**
     * 入团所需的控制面最小端口；生产适配 {@link ControlBootstrapService}，
     * 测试可注入 Fake。
     */
    public interface ControlPort {
        Optional<MappedIdentity> resolve(String qqId);
        int playerCount();
        ControlOperationResult registerPlayer(String playerId, String qqId,
                                               String displayName, String key, String reason);
        ControlOperationResult bindSecretary(String playerId, String key);
    }

    private final QqPlatformCapability platform;
    private final ControlPort control;
    private final OutboxSender outbox;
    private final DeliveryLedger ledger;
    private final Supplier<String> publicGroupId;
    private final ControlMcpConfig.PlatformPolicy policy;

    public PlayerOnboardingService(QqPlatformCapability platform,
                                   ControlBootstrapService control,
                                   OutboxSender outbox,
                                   DeliveryLedger ledger,
                                   Supplier<String> publicGroupId,
                                   ControlMcpConfig.PlatformPolicy policy) {
        this(platform, new ControlBootstrapPort(Objects.requireNonNull(control, "control")),
                outbox, ledger, publicGroupId, policy);
    }

    public PlayerOnboardingService(QqPlatformCapability platform,
                                   ControlPort control,
                                   OutboxSender outbox,
                                   DeliveryLedger ledger,
                                   Supplier<String> publicGroupId,
                                   ControlMcpConfig.PlatformPolicy policy) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.control = Objects.requireNonNull(control, "control");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.publicGroupId = Objects.requireNonNull(publicGroupId, "publicGroupId");
        this.policy = policy == null ? ControlMcpConfig.PlatformPolicy.defaults() : policy;
    }

    public record Result(String playerId, String qqId, boolean secretaryBound,
                         boolean alreadyRegistered, boolean alreadyClaimed,
                         String deliveryState, List<String> messageIds) {
        public Result {
            messageIds = List.copyOf(messageIds);
        }
    }

    public Result onboard(Actor supervisor, String sourceGroupId, String sourceMessageId,
                          String qqId, String displayName, String registrationForm,
                          String idempotencyKey) {
        requireSupervisor(supervisor);
        if (!policy.onboardingEnabled()) {
            throw new OnboardingException("ONBOARDING_DISABLED", "自主入团当前未启用");
        }
        String groupId = requireText(sourceGroupId, "sourceGroupId", 64);
        String messageId = requireText(sourceMessageId, "sourceMessageId", 128);
        String targetQq = requireText(qqId, "qqId", 64);
        String key = requireText(idempotencyKey, "idempotencyKey", 200);
        assertAllowedGroup(groupId);

        ChatMessageDetail source = readMessage(messageId);
        if (source.sessionType() != SessionType.GROUP
                || !groupId.equals(source.targetId())) {
            throw new OnboardingException("SOURCE_MESSAGE_GROUP_MISMATCH",
                    "来源消息不属于指定群");
        }
        if (!targetQq.equals(source.senderId())) {
            throw new OnboardingException("SOURCE_SENDER_MISMATCH",
                    "来源消息发送者与 qqId 不一致");
        }
        if (policy.onboardingRequireOptIn()
                && !OPT_IN.matcher(source.text() == null ? "" : source.text()).find()) {
            throw new OnboardingException("OPT_IN_REQUIRED",
                    "来源消息没有报名/加入意图");
        }

        String requestedName = displayName == null ? "" : displayName.trim();
        Optional<MappedIdentity> existing = control.resolve(targetQq);
        String playerId;
        String resolvedName;
        boolean alreadyRegistered;
        if (existing.isPresent()) {
            MappedIdentity identity = existing.orElseThrow();
            if (!identity.isPlayer()) {
                throw new OnboardingException("IDENTITY_CONFLICT",
                        "该 QQ 已是推演/管理身份，不能作为玩家入团");
            }
            playerId = identity.profile().playerId();
            resolvedName = identity.profile().displayName();
            alreadyRegistered = true;
        } else {
            int playerCount = control.playerCount();
            if (playerCount >= policy.onboardingMaxPlayers()) {
                throw new OnboardingException("PLAYER_LIMIT_REACHED", "玩家数量已达到上限");
            }
            resolvedName = requestedName.isBlank()
                    ? (source.senderName().isBlank() ? targetQq : source.senderName())
                    : requestedName;
            playerId = derivedPlayerId(targetQq);
            String digest = Hashes.sha256("onboarding:" + key);
            ControlOperationResult registered = control.registerPlayer(
                    playerId, targetQq, resolvedName,
                    "onboarding:" + digest + ":register",
                    "auto onboarding from group message " + messageId);
            if (registered == null || !registered.ok()) {
                throw new OnboardingException(
                        registered == null ? "REGISTRATION_FAILED" : registered.code(),
                        registered == null ? "玩家注册失败" : registered.message());
            }
            playerId = String.valueOf(registered.data().getOrDefault("playerId", playerId));
            alreadyRegistered = false;
        }

        String digest = Hashes.sha256("onboarding:" + key);
        ControlOperationResult bound = control.bindSecretary(
                playerId, "onboarding:" + digest + ":bind");
        if (bound == null || !bound.ok()) {
            throw new OnboardingException(
                    bound == null ? "SECRETARY_BIND_FAILED" : bound.code(),
                    bound == null ? "秘书绑定失败" : bound.message());
        }

        // 单 QQ 只发送一张报名卡；换一条来源消息也不能重复私信。
        String ledgerKey = "onboarding:" + Hashes.sha256(
                "player:" + playerId) + ":registration-card";
        boolean claimed;
        try {
            claimed = ledger.claim(ledgerKey);
        } catch (RuntimeException failure) {
            throw new OnboardingException("DELIVERY_LEDGER_UNAVAILABLE",
                    "报名卡投递声明失败，未发送");
        }
        if (!claimed) {
            return new Result(playerId, targetQq, true, alreadyRegistered, true,
                    "ALREADY_ATTEMPTED", List.of());
        }

        String form = registrationForm == null || registrationForm.isBlank()
                ? DEFAULT_REGISTRATION_FORM : registrationForm;
        List<SendOutcome> outcomes = outbox.sendPrivateToUser(
                targetQq, groupId, List.of(form),
                "onboarding:" + digest + ":registration-card");
        if (outcomes.isEmpty()) {
            return new Result(playerId, targetQq, true, alreadyRegistered, false,
                    "UNKNOWN", List.of());
        }
        SendOutcome outcome = outcomes.getFirst();
        String state = switch (outcome.state()) {
            case SENT -> "SENT";
            case FAILED_BEFORE_SEND -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
        return new Result(playerId, targetQq, true, alreadyRegistered, false,
                state, outcome.adapterMessageIds());
    }

    private ChatMessageDetail readMessage(String messageId) {
        try {
            return platform.message(messageId).toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new OnboardingException("SOURCE_MESSAGE_TIMEOUT", "来源消息读取超时");
        } catch (ExecutionException failure) {
            throw new OnboardingException("SOURCE_MESSAGE_NOT_FOUND",
                    "来源消息不存在或当前不可访问");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OnboardingException("SOURCE_MESSAGE_INTERRUPTED", "来源消息读取被中断");
        } catch (RuntimeException failure) {
            throw new OnboardingException("SOURCE_MESSAGE_UNAVAILABLE",
                    "来源消息读取失败");
        }
    }

    private void assertAllowedGroup(String groupId) {
        Set<String> allowed = policy.onboardingAllowedGroupIds();
        if (allowed.contains("*")) {
            return;
        }
        if (allowed.isEmpty()) {
            String configured = publicGroupId.get();
            if (configured != null && configured.equals(groupId)) {
                return;
            }
            throw new OnboardingException("GROUP_NOT_ALLOWED",
                    "该群不在自主入团 allowlist");
        }
        if (!allowed.contains(groupId)) {
            throw new OnboardingException("GROUP_NOT_ALLOWED",
                    "该群不在自主入团 allowlist");
        }
    }

    private static final class ControlBootstrapPort implements ControlPort {
        private final ControlBootstrapService delegate;

        private ControlBootstrapPort(ControlBootstrapService delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MappedIdentity> resolve(String qqId) {
            return delegate.resolve(qqId);
        }

        @Override
        public int playerCount() {
            Object value = delegate.summary().get("playerCount");
            return value instanceof Number number ? number.intValue() : 0;
        }

        @Override
        public ControlOperationResult registerPlayer(String playerId, String qqId,
                                                     String displayName, String key,
                                                     String reason) {
            return delegate.registerPlayer(playerId, qqId, displayName, key, reason);
        }

        @Override
        public ControlOperationResult bindSecretary(String playerId, String key) {
            return delegate.bindSecretary(playerId, key);
        }
    }

    private static String derivedPlayerId(String qqId) {
        return "player-" + Hashes.sha256("qq:" + qqId).substring(0, 20);
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new OnboardingException("INVALID_ARGUMENTS", name + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new OnboardingException("INVALID_ARGUMENTS", name + " 超过长度限制");
        }
        return trimmed;
    }

    private static void requireSupervisor(Actor actor) {
        if (actor == null || actor.role() != ActorRole.SUPERVISOR) {
            throw new OnboardingException("FORBIDDEN", "只有 supervisor 可以执行自主入团");
        }
    }

    /** 稳定失败码；message 不含正文、token 或路径。 */
    public static final class OnboardingException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public OnboardingException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
