package io.mosire.iibot.app.dispatch;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.agent.InteractionReply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InboundDispatcherTest {

    @Test
    void sameUserIsSerialAndDifferentUsersRunConcurrently() throws Exception {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> startedOrder = new ConcurrentLinkedQueue<>();
        AtomicInteger global = new AtomicInteger();
        AtomicInteger maxGlobal = new AtomicInteger();

        InboundProcessor processor = message -> {
            int current = global.incrementAndGet();
            maxGlobal.accumulateAndGet(current, Math::max);
            startedOrder.add(message.dedupeKey());
            try {
                if ("same-1".equals(message.dedupeKey())) {
                    firstStarted.countDown();
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } else if ("user-a".equals(message.dedupeKey())
                        || "user-b".equals(message.dedupeKey())) {
                    bothStarted.countDown();
                    bothStarted.await(2, TimeUnit.SECONDS);
                }
                return List.of(new InteractionReply("ok-" + message.dedupeKey()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                global.decrementAndGet();
            }
        };
        InboundDispatcher dispatcher = dispatcher(processor, adapter);

        try {
            dispatcher.submit(TestSupport.privateMessage("u1", "a", "same-1"));
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            dispatcher.submit(TestSupport.privateMessage("u1", "b", "same-2"));
            Thread.sleep(80);
            assertThat(startedOrder).as("同一用户第二条必须等待第一条完成")
                    .containsExactly("same-1");

            releaseFirst.countDown();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(3))).isTrue();
            assertThat(startedOrder).containsExactly("same-1", "same-2");

            dispatcher.submit(TestSupport.privateMessage("uA", "a", "user-a"));
            dispatcher.submit(TestSupport.privateMessage("uB", "b", "user-b"));
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(3))).isTrue();
            assertThat(maxGlobal.get()).as("不同用户可以并发").isGreaterThanOrEqualTo(2);
            assertThat(adapter.sent()).hasSize(4);
        } finally {
            releaseFirst.countDown();
            dispatcher.close();
        }
    }

    @Test
    void duplicateDedupeKeyIsIgnoredAtAppLayer() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AtomicInteger calls = new AtomicInteger();
        InboundDispatcher dispatcher = dispatcher(message -> {
            calls.incrementAndGet();
            return List.of(new InteractionReply("once"));
        }, adapter);
        try {
            InboundMessage message = TestSupport.privateMessage("u1", "x", "same-key");
            assertThat(dispatcher.submit(message)).isTrue();
            assertThat(dispatcher.submit(message)).isFalse();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(calls).hasValue(1);
            assertThat(adapter.sent()).hasSize(1);
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void handlerExceptionDoesNotKillSubsequentMessages() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AtomicInteger calls = new AtomicInteger();
        InboundDispatcher dispatcher = dispatcher(message -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom with sensitive value sk-123");
            }
            return List.of(new InteractionReply("after-error"));
        }, adapter);
        try {
            dispatcher.submit(TestSupport.privateMessage("u1", "x", "boom"));
            dispatcher.submit(TestSupport.privateMessage("u1", "y", "after"));
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(calls).hasValue(2);
            assertThat(adapter.sent()).hasSize(1);
            assertThat(adapter.sent().get(0).textSegments()).containsExactly("after-error");
        } finally {
            dispatcher.close();
        }
    }

    private static InboundDispatcher dispatcher(InboundProcessor processor,
                                                FakeMessagingAdapter adapter) {
        return new InboundDispatcher(processor, new OutboxSender(adapter, Duration.ofSeconds(2)),
                16, 8, 100);
    }
}
