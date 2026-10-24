package io.mosire.iibot.app.control;

import io.mosire.iibot.app.delivery.JsonDeliveryLedger;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.mcp.ControlMcpConfig;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.agent.MappedIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 公屏自主入团：来源证据、权限策略、幂等注册和报名卡三态。 */
class PlayerOnboardingServiceTest {

    private static final Actor SUPERVISOR = new Actor("supervisor-1", ActorRole.SUPERVISOR);

    @TempDir
    Path tempDir;

    @Test
    void onboardsVerifiedPublicPlayerAndSendsTemporaryPrivateCard() {
        Harness harness = new Harness();
        PlayerOnboardingService.Result result = harness.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-1");

        assertThat(result.playerId()).startsWith("player-");
        assertThat(result.secretaryBound()).isTrue();
        assertThat(result.deliveryState()).isEqualTo("SENT");
        assertThat(harness.control.registerCalls).hasSize(1);
        assertThat(harness.control.bindCalls).hasSize(1);
        assertThat(harness.adapter.sent()).singleElement().satisfies(message -> {
            assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
            assertThat(message.targetId()).isEqualTo("20002");
            assertThat(message.temporaryGroupId()).isEqualTo("30003");
            assertThat(message.textSegments().getFirst())
                    .contains("七柱之下", "姓名", "职位");
        });
    }

    @Test
    void repeatCallDoesNotSendSecondRegistrationCard() {
        Harness harness = new Harness();
        PlayerOnboardingService.Result first = harness.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-repeat");
        PlayerOnboardingService.Result second = harness.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-repeat");

        assertThat(second.playerId()).isEqualTo(first.playerId());
        assertThat(second.alreadyClaimed()).isTrue();
        assertThat(second.deliveryState()).isEqualTo("ALREADY_ATTEMPTED");
        assertThat(harness.adapter.sent()).hasSize(1);
    }

    @Test
    void rejectsGroupOutsideAllowlistBeforeMessageRead() {
        Harness harness = new Harness();
        assertThatThrownBy(() -> harness.service.onboard(
                SUPERVISOR, "99999", "m-1", "20002", "小明", null, "join-bad-group"))
                .isInstanceOfSatisfying(PlayerOnboardingService.OnboardingException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GROUP_NOT_ALLOWED"));
        assertThat(harness.platform.messageCalls).isZero();
    }

    @Test
    void rejectsSenderMismatchAndMissingOptIn() {
        Harness mismatch = new Harness();
        mismatch.platform.senderId = "88888";
        assertThatThrownBy(() -> mismatch.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-mismatch"))
                .isInstanceOfSatisfying(PlayerOnboardingService.OnboardingException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SOURCE_SENDER_MISMATCH"));

        Harness noOptIn = new Harness();
        noOptIn.platform.text = "今天天气不错";
        assertThatThrownBy(() -> noOptIn.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-no-optin"))
                .isInstanceOfSatisfying(PlayerOnboardingService.OnboardingException.class,
                        failure -> assertThat(failure.code()).isEqualTo("OPT_IN_REQUIRED"));
    }

    @Test
    void rejectsWhenPlayerLimitReached() {
        Harness harness = new Harness();
        harness.control.playerCount = 1;
        harness.service = harness.newService(new ControlMcpConfig.PlatformPolicy(
                false, true, Set.of("30003"), true, 1));

        assertThatThrownBy(() -> harness.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-limit"))
                .isInstanceOfSatisfying(PlayerOnboardingService.OnboardingException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PLAYER_LIMIT_REACHED"));
        assertThat(harness.adapter.sent()).isEmpty();
    }

    @Test
    void failedCardDeliveryKeepsRegistrationAndNeverAutoRetries() {
        Harness harness = new Harness();
        harness.adapter = new FakeMessagingAdapter(message -> CompletableFuture.completedFuture(
                SendReceipt.failed("ONEBOT_RETCODE_100", "rejected")));
        harness.service = harness.newService(harness.policy);

        PlayerOnboardingService.Result result = harness.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-fail");
        assertThat(result.deliveryState()).isEqualTo("FAILED");

        PlayerOnboardingService.Result retry = harness.service.onboard(
                SUPERVISOR, "30003", "m-1", "20002", "小明", null, "join-fail");
        assertThat(retry.deliveryState()).isEqualTo("ALREADY_ATTEMPTED");
        assertThat(harness.adapter.sent()).hasSize(1);
    }

    private final class Harness {
        private FakePlatform platform = new FakePlatform();
        private FakeControlPort control = new FakeControlPort();
        private FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        private ControlMcpConfig.PlatformPolicy policy = new ControlMcpConfig.PlatformPolicy(
                false, true, Set.of("30003"), true, 64);
        private PlayerOnboardingService service = newService(policy);

        private PlayerOnboardingService newService(ControlMcpConfig.PlatformPolicy selected) {
            OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));
            JsonDeliveryLedger ledger = new JsonDeliveryLedger(tempDir);
            return new PlayerOnboardingService(platform, control, outbox, ledger,
                    () -> "30003", selected);
        }
    }

    private static final class FakeControlPort implements PlayerOnboardingService.ControlPort {
        private final List<String> registerCalls = new ArrayList<>();
        private final List<String> bindCalls = new ArrayList<>();
        private int playerCount;
        private MappedIdentity existing;

        @Override
        public Optional<MappedIdentity> resolve(String qqId) {
            return existing != null && existing.qqId().equals(qqId)
                    ? Optional.of(existing) : Optional.empty();
        }

        @Override
        public int playerCount() {
            return playerCount;
        }

        @Override
        public ControlOperationResult registerPlayer(String playerId, String qqId,
                                                     String displayName, String key,
                                                     String reason) {
            registerCalls.add(playerId);
            playerCount++;
            existing = new MappedIdentity(qqId,
                    new Actor(playerId, ActorRole.PLAYER),
                    new PlayerProfile(playerId, qqId, displayName));
            return ControlOperationResult.success(Map.of("playerId", playerId));
        }

        @Override
        public ControlOperationResult bindSecretary(String playerId, String key) {
            bindCalls.add(playerId);
            return ControlOperationResult.success(Map.of("playerId", playerId));
        }
    }

    private static final class FakePlatform implements QqPlatformCapability {
        private int messageCalls;
        private String senderId = "20002";
        private String text = "我想报名加入";

        @Override
        public CompletableFuture<List<ChatConversation>> recentConversations(int count) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<ChatConversation>> joinedGroups() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<ChatConversation>> friends(boolean noCache) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<GroupMember>> groupMembers(String groupId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ChatMessageDetail> message(String messageId) {
            messageCalls++;
            return CompletableFuture.completedFuture(new ChatMessageDetail(
                    messageId, SessionType.GROUP, "30003", senderId, "小明",
                    text, java.time.Instant.parse("2026-10-24T00:00:00Z"), 0));
        }

        @Override
        public CompletableFuture<SendReceipt> sendPrivate(String targetQqId,
                                                          String temporaryGroupId,
                                                          List<String> textSegments,
                                                          String idempotencyKey) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<SendReceipt> sendGroup(String groupId,
                                                        List<String> textSegments,
                                                        String idempotencyKey) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<ChatHistoryPage> history(ChatHistoryQuery query) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }
    }
}
