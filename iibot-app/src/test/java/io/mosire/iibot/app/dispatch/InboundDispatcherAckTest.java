package io.mosire.iibot.app.dispatch;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.agent.InteractionReply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InboundDispatcherAckTest {

    private static final AckPolicy TWO_ACCOUNT_POLICY = AckPolicy.configured(Map.of(
            "qq-player", AckPolicy.PLAYER_ACK_TEXT,
            "qq-director", AckPolicy.DIRECTOR_ACK_TEXT));

    @Test
    void registeredPlayerPrivateAckPrecedesFormalReplyWithDistinctKeys() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AtomicReference<InboundMessage> processed = new AtomicReference<>();
        InboundDispatcher dispatcher = dispatcher(message -> {
            processed.set(message);
            return List.of(new InteractionReply("正式回复"));
        }, adapter, TWO_ACCOUNT_POLICY, Duration.ofSeconds(2));

        try {
            InboundMessage inbound = TestSupport.privateMessage("qq-player", "原始消息", "dedupe-p");
            assertThat(dispatcher.submit(inbound)).isTrue();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(adapter.sent()).hasSize(2);
            OutboundMessage ack = adapter.sent().get(0);
            OutboundMessage reply = adapter.sent().get(1);
            assertThat(ack.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
            assertThat(ack.targetId()).isEqualTo("qq-player");
            assertThat(ack.textSegments()).containsExactly(AckPolicy.PLAYER_ACK_TEXT);
            assertThat(ack.idempotencyKey()).contains("ack:dedupe-p");
            assertThat(reply.textSegments()).containsExactly("正式回复");
            assertThat(reply.idempotencyKey()).isEqualTo("inbound:dedupe-p:reply:0")
                    .isNotEqualTo(ack.idempotencyKey());
            assertThat(processed.get()).isSameAs(inbound);
            assertThat(processed.get().text()).doesNotContain(AckPolicy.PLAYER_ACK_TEXT);
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void registeredDirectorPrivateUsesGmAck() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        InboundDispatcher dispatcher = dispatcher(message -> List.of(new InteractionReply("GM reply")),
                adapter, TWO_ACCOUNT_POLICY, Duration.ofSeconds(2));
        try {
            assertThat(dispatcher.submit(TestSupport.privateMessage(
                    "qq-director", "/开回合 30", "dedupe-d"))).isTrue();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(adapter.sent()).hasSize(2);
            assertThat(adapter.sent().get(0).textSegments())
                    .containsExactly(AckPolicy.DIRECTOR_ACK_TEXT);
            assertThat(adapter.sent().get(0).idempotencyKey()).contains("ack:dedupe-d");
            assertThat(adapter.sent().get(1).textSegments()).containsExactly("GM reply");
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void unknownGroupAndSelfMessagesNeverAck() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        InboundDispatcher dispatcher = dispatcher(message -> List.of(), adapter,
                TWO_ACCOUNT_POLICY, Duration.ofSeconds(2));
        try {
            assertThat(dispatcher.submit(TestSupport.privateMessage(
                    "qq-unknown", "hello", "unknown"))).isTrue();
            assertThat(dispatcher.submit(TestSupport.groupMessage(
                    "qq-player", "group-1", "hello", "group"))).isTrue();
            assertThat(dispatcher.submit(new InboundMessage("adapter-1", "bot-1", "self-msg",
                    "bot-1", InboundMessage.SessionType.PRIVATE, null, "self",
                    TestSupport.BASE, "self"))).isTrue();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(adapter.sent()).as("未登记/群聊/自身消息不产生 ACK").isEmpty();
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void duplicateDedupeKeyNeverRepeatsAckOrFormalReply() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AtomicInteger calls = new AtomicInteger();
        InboundDispatcher dispatcher = dispatcher(message -> {
            calls.incrementAndGet();
            return List.of(new InteractionReply("reply"));
        }, adapter, TWO_ACCOUNT_POLICY, Duration.ofSeconds(2));
        try {
            InboundMessage inbound = TestSupport.privateMessage("qq-player", "x", "same");
            assertThat(dispatcher.submit(inbound)).isTrue();
            assertThat(dispatcher.submit(inbound)).isFalse();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(calls).hasValue(1);
            assertThat(adapter.sent()).hasSize(2);
            assertThat(adapter.sent()).filteredOn(message ->
                            message.textSegments().contains(AckPolicy.PLAYER_ACK_TEXT))
                    .hasSize(1);
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void ackUnknownIsNotRetriedAndDoesNotPreventFormalReply() throws Exception {
        AtomicInteger ackSends = new AtomicInteger();
        AtomicInteger replySends = new AtomicInteger();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message -> {
            if (message.idempotencyKey().contains(":ack:")) {
                ackSends.incrementAndGet();
                return CompletableFuture.completedFuture(
                        SendReceipt.unknown("ACK_TIMEOUT", "no receipt"));
            }
            replySends.incrementAndGet();
            return CompletableFuture.completedFuture(SendReceipt.sent(List.of("reply-id")));
        });
        InboundDispatcher dispatcher = dispatcher(message -> List.of(new InteractionReply("正式回复")),
                adapter, TWO_ACCOUNT_POLICY, Duration.ofSeconds(2));
        try {
            assertThat(dispatcher.submit(TestSupport.privateMessage(
                    "qq-player", "x", "ack-unknown"))).isTrue();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(ackSends).hasValue(1);
            assertThat(replySends).hasValue(1);
            assertThat(adapter.sent()).hasSize(2);
            assertThat(adapter.sent()).extracting(OutboundMessage::idempotencyKey)
                    .anySatisfy(key -> assertThat(key).contains("ack:ack-unknown"))
                    .anySatisfy(key -> assertThat(key).isEqualTo(
                            "inbound:ack-unknown:reply:0"));
            Thread.sleep(80);
            assertThat(ackSends).as("UNKNOWN 不自动重发").hasValue(1);
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void ackFailedBeforeSendIsNotRetriedAndDoesNotPreventFormalReply() {
        AtomicInteger ackSends = new AtomicInteger();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message -> {
            if (message.idempotencyKey().contains(":ack:")) {
                ackSends.incrementAndGet();
                return CompletableFuture.completedFuture(
                        SendReceipt.failed("ACK_REJECTED", "rejected"));
            }
            return CompletableFuture.completedFuture(SendReceipt.sent(List.of("reply-id")));
        });
        InboundDispatcher dispatcher = dispatcher(message -> List.of(new InteractionReply("正式回复")),
                adapter, TWO_ACCOUNT_POLICY, Duration.ofSeconds(2));
        try {
            assertThat(dispatcher.submit(TestSupport.privateMessage(
                    "qq-player", "x", "ack-failed"))).isTrue();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(ackSends).hasValue(1);
            assertThat(adapter.sent()).hasSize(2);
            assertThat(adapter.sent()).anySatisfy(message ->
                    assertThat(message.textSegments()).containsExactly("正式回复"));
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void callbackDoesNotWaitForAckAndCloseWaitsForInFlightAck() throws Exception {
        CountDownLatch ackStarted = new CountDownLatch(1);
        CountDownLatch releaseAck = new CountDownLatch(1);
        CountDownLatch processorStarted = new CountDownLatch(1);
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message -> {
            ackStarted.countDown();
            try {
                assertThat(releaseAck.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(SendReceipt.sent(List.of("ack-id")));
        });
        InboundDispatcher dispatcher = dispatcher(message -> {
            processorStarted.countDown();
            return List.of();
        }, adapter, TWO_ACCOUNT_POLICY, Duration.ofSeconds(5));
        try {
            long before = System.nanoTime();
            dispatcher.onMessage(TestSupport.privateMessage("qq-player", "x", "slow-ack"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertThat(elapsedMillis).as("adapter 回调不能等待 ACK 回执").isLessThan(500);
            assertThat(ackStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(processorStarted.await(1, TimeUnit.SECONDS))
                    .as("ACK 未完成时正式处理仍应开始").isTrue();
            assertThat(dispatcher.pendingAckCount()).isEqualTo(1);

            Thread closer = new Thread(dispatcher::close, "close-test");
            closer.start();
            Thread.sleep(80);
            assertThat(closer.isAlive()).as("close 应等待在途 ACK 结束").isTrue();

            releaseAck.countDown();
            closer.join(3000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(dispatcher.pendingAckCount()).isZero();
            assertThat(dispatcher.submit(TestSupport.privateMessage(
                    "qq-player", "after-close", "after-close"))).isFalse();
        } finally {
            releaseAck.countDown();
            dispatcher.close();
        }
    }

    @Test
    void concurrentUsersCompleteAcksAndRepliesWithoutLeakingTasks() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        Map<String, String> mapping = new java.util.LinkedHashMap<>();
        List<InboundMessage> inbound = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String qq = "concurrent-" + i;
            mapping.put(qq, AckPolicy.PLAYER_ACK_TEXT);
            inbound.add(TestSupport.privateMessage(qq, "x", "concurrent-" + i));
        }
        InboundDispatcher dispatcher = dispatcher(message ->
                        List.of(new InteractionReply("reply-" + message.userId())),
                adapter, AckPolicy.configured(mapping), Duration.ofSeconds(2));
        try {
            inbound.forEach(message -> assertThat(dispatcher.submit(message)).isTrue());
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(dispatcher.pendingAckCount()).isZero();
            assertThat(dispatcher.sessionCount()).isZero();
            assertThat(adapter.sent()).hasSize(48);
            assertThat(adapter.sent()).filteredOn(message ->
                            message.textSegments().contains(AckPolicy.PLAYER_ACK_TEXT))
                    .hasSize(24);
        } finally {
            dispatcher.close();
        }
    }

    private static InboundDispatcher dispatcher(InboundProcessor processor,
                                                FakeMessagingAdapter adapter,
                                                AckPolicy ackPolicy,
                                                Duration sendTimeout) {
        return new InboundDispatcher(processor,
                new OutboxSender(adapter, sendTimeout), ackPolicy, 64, 32, 1_000);
    }
}
