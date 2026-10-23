package io.mosire.iibot.connect.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** {@link AdapterRegistry} 的并发与不可变快照契约。 */
class AdapterRegistryTest {

    @Test
    void findListAndUnregisterAreSnapshots() {
        AdapterRegistry registry = new AdapterRegistry(List.of(
                new MinimalAdapter("b"), new MinimalAdapter("a")));

        assertThat(registry.adapterIds()).containsExactly("a", "b");
        assertThat(registry.find("a")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> registry.list().add(new MinimalAdapter("c")))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(registry.unregister("a")).isPresent();
        assertThat(registry.find("a")).isEmpty();
        assertThat(registry.list()).extracting(MessagingAdapter::adapterId).containsExactly("b");
    }

    @Test
    void duplicateAndUnsafeIdsAreRejected() {
        AdapterRegistry registry = new AdapterRegistry();
        registry.register(new MinimalAdapter("safe:id"));
        assertThatThrownBy(() -> registry.register(new MinimalAdapter("safe:id")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(new MinimalAdapter("../escape")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(new MinimalAdapter("bad id ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRegistrationProducesCompleteSnapshot() throws Exception {
        AdapterRegistry registry = new AdapterRegistry();
        int count = 64;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int id = index;
                futures.add(executor.submit(() -> registry.register(new MinimalAdapter("adapter-" + id))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(registry.size()).isEqualTo(count);
        assertThat(registry.list()).hasSize(count);
        assertThat(registry.adapterIds()).hasSize(count);
    }

    /** 只满足 SPI 的最小测试适配器。 */
    private static final class MinimalAdapter implements MessagingAdapter {

        private final String id;

        private MinimalAdapter(String id) {
            this.id = id;
        }

        @Override
        public String adapterId() {
            return id;
        }

        @Override
        public void start(InboundHandler handler) {
            // 无入站。
        }

        @Override
        public CompletionStage<SendReceipt> send(OutboundMessage message) {
            return CompletableFuture.completedFuture(SendReceipt.sent(List.of("id-" + id)));
        }

        @Override
        public AdapterStatus status() {
            return AdapterStatus.stopped();
        }

        @Override
        public void addEventListener(AdapterEventListener listener) {
            // 无事件。
        }

        @Override
        public void removeEventListener(AdapterEventListener listener) {
            // 无事件。
        }

        @Override
        public void close() {
            // 无资源。
        }
    }
}
