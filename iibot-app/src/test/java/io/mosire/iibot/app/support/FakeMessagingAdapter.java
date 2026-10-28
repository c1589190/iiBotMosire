package io.mosire.iibot.app.support;

import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 离线 MessagingAdapter：记录出站消息并允许投递入站消息。 */
public final class FakeMessagingAdapter implements MessagingAdapter {

    private final String adapterId;
    private final Function<OutboundMessage, CompletionStage<SendReceipt>> sender;
    private final boolean failOnStart;
    private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile InboundHandler handler;

    public FakeMessagingAdapter() {
        this("adapter-1");
    }

    public FakeMessagingAdapter(String adapterId) {
        this(adapterId, false);
    }

    public FakeMessagingAdapter(String adapterId, boolean failOnStart) {
        this.adapterId = java.util.Objects.requireNonNull(adapterId, "adapterId");
        this.failOnStart = failOnStart;
        this.sender = message -> CompletableFuture.completedFuture(
                SendReceipt.sent(List.of("fake-" + counter.incrementAndGet())));
    }

    public FakeMessagingAdapter(Function<OutboundMessage, CompletionStage<SendReceipt>> sender) {
        this("adapter-1", sender);
    }

    public FakeMessagingAdapter(String adapterId,
                                Function<OutboundMessage, CompletionStage<SendReceipt>> sender) {
        this.adapterId = java.util.Objects.requireNonNull(adapterId, "adapterId");
        this.failOnStart = false;
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public void start(InboundHandler inboundHandler) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("已启动");
        }
        if (failOnStart) {
            throw new IllegalStateException("模拟启动失败");
        }
        this.handler = inboundHandler;
    }

    @Override
    public CompletionStage<SendReceipt> send(OutboundMessage message) {
        sent.add(message);
        return sender.apply(message);
    }

    public void deliver(InboundMessage message) {
        InboundHandler current = handler;
        if (current == null) {
            throw new IllegalStateException("adapter 尚未启动");
        }
        current.onMessage(message);
    }

    public List<OutboundMessage> sent() {
        return List.copyOf(sent);
    }

    @Override
    public AdapterStatus status() {
        return new AdapterStatus(AdapterStatus.ConnectionState.CONNECTED, Instant.now(), null, null);
    }

    @Override
    public void addEventListener(AdapterEventListener listener) {
    }

    @Override
    public void removeEventListener(AdapterEventListener listener) {
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public boolean isClosed() {
        return closed.get();
    }
}
