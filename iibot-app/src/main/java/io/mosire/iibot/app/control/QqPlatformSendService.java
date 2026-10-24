package io.mosire.iibot.app.control;

import io.mosire.iibot.app.delivery.DeliveryLedger;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.SendOutcome;
import io.mosire.iibot.app.mcp.ControlMcpConfig;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;

import java.util.List;
import java.util.Objects;

/**
 * 平台级任意发送服务。默认关闭，只有 {@code control.platform.allowArbitrarySend=true}
 * 时由组合根构建并暴露 MCP 工具。
 *
 * <p>所有发送仍走 OutboxSender 的正文守卫、限速、三态回执和 delivery ledger；
 * 不提供 raw OneBot action，不做自动重试。</p>
 */
public final class QqPlatformSendService {

    private final OutboxSender outbox;
    private final DeliveryLedger ledger;
    private final ControlMcpConfig.PlatformPolicy policy;

    public QqPlatformSendService(OutboxSender outbox, DeliveryLedger ledger,
                                 ControlMcpConfig.PlatformPolicy policy) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.policy = policy == null ? ControlMcpConfig.PlatformPolicy.defaults() : policy;
    }

    public record Result(String deliveryState, List<String> messageIds,
                         boolean alreadyAttempted) {
        public Result {
            messageIds = List.copyOf(messageIds);
        }
    }

    public Result sendPrivate(Actor supervisor, String qqId, String temporaryGroupId,
                              String text, String idempotencyKey) {
        assertAllowed(supervisor);
        String target = requireText(qqId, "qqId", 64);
        String key = requireText(idempotencyKey, "idempotencyKey", 200);
        if (text == null || text.isBlank() || text.length() > 4_000) {
            throw new PlatformSendException("INVALID_ARGUMENTS", "text 为空或超过长度限制");
        }
        String tempGroup = temporaryGroupId == null || temporaryGroupId.isBlank()
                ? null : temporaryGroupId.trim();
        String ledgerKey = "platform-send:private:" + Hashes.sha256(key);
        if (!claim(ledgerKey)) {
            return new Result("ALREADY_ATTEMPTED", List.of(), true);
        }
        List<SendOutcome> outcomes = outbox.sendPrivateToUser(
                target, tempGroup, List.of(text), "platform-send:" + Hashes.sha256(key));
        return result(outcomes);
    }

    public Result sendGroup(Actor supervisor, String groupId, String text,
                            String idempotencyKey) {
        assertAllowed(supervisor);
        String target = requireText(groupId, "groupId", 64);
        String key = requireText(idempotencyKey, "idempotencyKey", 200);
        if (text == null || text.isBlank() || text.length() > 10_000) {
            throw new PlatformSendException("INVALID_ARGUMENTS", "text 为空或超过长度限制");
        }
        String ledgerKey = "platform-send:group:" + Hashes.sha256(key);
        if (!claim(ledgerKey)) {
            return new Result("ALREADY_ATTEMPTED", List.of(), true);
        }
        List<SendOutcome> outcomes = outbox.broadcastToPublicGroup(
                target, List.of(text), "platform-send:" + Hashes.sha256(key));
        return result(outcomes);
    }

    private Result result(List<SendOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return new Result("UNKNOWN", List.of(), false);
        }
        SendOutcome outcome = outcomes.getFirst();
        String state = switch (outcome.state()) {
            case SENT -> "SENT";
            case FAILED_BEFORE_SEND -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
        return new Result(state, outcome.adapterMessageIds(), false);
    }

    private boolean claim(String key) {
        try {
            return ledger.claim(key);
        } catch (RuntimeException failure) {
            throw new PlatformSendException("DELIVERY_LEDGER_UNAVAILABLE",
                    "投递声明失败，未发送");
        }
    }

    private void assertAllowed(Actor supervisor) {
        if (supervisor == null || supervisor.role() != ActorRole.SUPERVISOR) {
            throw new PlatformSendException("FORBIDDEN", "只有 supervisor 可以平台发送");
        }
        if (!policy.allowArbitrarySend()) {
            throw new PlatformSendException("PLATFORM_SEND_DISABLED", "平台任意发送未启用");
        }
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new PlatformSendException("INVALID_ARGUMENTS", name + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new PlatformSendException("INVALID_ARGUMENTS", name + " 超过长度限制");
        }
        return trimmed;
    }

    public static final class PlatformSendException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public PlatformSendException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
