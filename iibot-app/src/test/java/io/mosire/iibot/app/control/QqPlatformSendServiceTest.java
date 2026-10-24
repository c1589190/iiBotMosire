package io.mosire.iibot.app.control;

import io.mosire.iibot.app.delivery.JsonDeliveryLedger;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.mcp.ControlMcpConfig;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 平台任意发送默认关闭、显式开启后幂等且不自动重试。 */
class QqPlatformSendServiceTest {

    private static final Actor SUPERVISOR = new Actor("supervisor-1", ActorRole.SUPERVISOR);

    @TempDir
    Path tempDir;

    @Test
    void disabledPolicyFailsBeforeSending() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        QqPlatformSendService service = service(adapter, disabledPolicy());

        assertThatThrownBy(() -> service.sendPrivate(
                SUPERVISOR, "20002", null, "hello", "platform-disabled"))
                .isInstanceOfSatisfying(QqPlatformSendService.PlatformSendException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PLATFORM_SEND_DISABLED"));
        assertThat(adapter.sent()).isEmpty();
    }

    @Test
    void enabledPolicySendsPrivateTemporaryAndDeduplicates() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        QqPlatformSendService service = service(adapter, enabledPolicy());

        QqPlatformSendService.Result first = service.sendPrivate(
                SUPERVISOR, "20002", "30003", "hello", "platform-private-1");
        QqPlatformSendService.Result second = service.sendPrivate(
                SUPERVISOR, "20002", "30003", "hello", "platform-private-1");

        assertThat(first.deliveryState()).isEqualTo("SENT");
        assertThat(second.deliveryState()).isEqualTo("ALREADY_ATTEMPTED");
        assertThat(second.alreadyAttempted()).isTrue();
        assertThat(adapter.sent()).singleElement().satisfies(message -> {
            assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
            assertThat(message.targetId()).isEqualTo("20002");
            assertThat(message.temporaryGroupId()).isEqualTo("30003");
        });
    }

    @Test
    void enabledPolicySendsGroupToArbitraryAllowedTarget() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        QqPlatformSendService service = service(adapter, enabledPolicy());

        QqPlatformSendService.Result result = service.sendGroup(
                SUPERVISOR, "99999", "群公告", "platform-group-1");

        assertThat(result.deliveryState()).isEqualTo("SENT");
        assertThat(adapter.sent()).singleElement()
                .extracting(OutboundMessage::targetId).isEqualTo("99999");
    }

    private QqPlatformSendService service(FakeMessagingAdapter adapter,
                                          ControlMcpConfig.PlatformPolicy policy) {
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));
        return new QqPlatformSendService(outbox,
                new JsonDeliveryLedger(tempDir.resolve("ledger-" + System.nanoTime())), policy);
    }

    private static ControlMcpConfig.PlatformPolicy disabledPolicy() {
        return new ControlMcpConfig.PlatformPolicy(
                false, true, Set.of("30003"), true, 64);
    }

    private static ControlMcpConfig.PlatformPolicy enabledPolicy() {
        return new ControlMcpConfig.PlatformPolicy(
                true, true, Set.of("30003"), true, 64);
    }
}
