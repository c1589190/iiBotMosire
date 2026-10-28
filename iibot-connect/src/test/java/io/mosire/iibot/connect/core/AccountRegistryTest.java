package io.mosire.iibot.connect.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRegistryTest {

    @Test
    void registersFindsAndReturnsImmutableSortedSnapshot() {
        AccountRegistry registry = new AccountRegistry();
        FakeAdapter beta = new FakeAdapter("adapter-beta");
        FakeAdapter alpha = new FakeAdapter("adapter-alpha");

        registry.register("account-b", beta);
        registry.register("account-a", alpha);

        assertThat(registry.find("account-a")).containsSame(alpha);
        assertThat(registry.find("account-b")).containsSame(beta);
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.accountIds()).containsExactlyInAnyOrder("account-a", "account-b");
        List<AccountRegistry.RegisteredAccount> snapshot = registry.snapshot();
        assertThat(snapshot).extracting(AccountRegistry.RegisteredAccount::accountId)
                .containsExactly("account-a", "account-b");
        assertThat(snapshot).extracting(AccountRegistry.RegisteredAccount::adapter)
                .containsExactly(alpha, beta);
        assertThatThrownBy(() -> snapshot.add(
                new AccountRegistry.RegisteredAccount("account-c", new FakeAdapter("a-c"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankDuplicateAccountAndDuplicateAdapterIds() {
        AccountRegistry registry = new AccountRegistry();
        registry.register("account-a", new FakeAdapter("adapter-1"));

        assertThatThrownBy(() -> registry.register(" ", new FakeAdapter("adapter-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
        assertThatThrownBy(() -> registry.register("account-a", new FakeAdapter("adapter-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId 重复");
        assertThatThrownBy(() -> registry.register("account-b", new FakeAdapter("adapter-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapterId 重复");
        assertThatThrownBy(() -> registry.register("account-c",
                registry.find("account-a").orElseThrow()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapterId 重复");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void replacesAndUnregistersWithoutBreakingUniqueAdapterOwnership() {
        AccountRegistry registry = new AccountRegistry();
        FakeAdapter first = new FakeAdapter("adapter-1");
        FakeAdapter second = new FakeAdapter("adapter-2");
        FakeAdapter other = new FakeAdapter("adapter-other");
        registry.register("account-a", first);
        registry.register("account-b", other);

        assertThat(registry.replace("account-a", second)).containsSame(first);
        assertThat(registry.find("account-a")).containsSame(second);
        assertThat(registry.replace("account-c", new FakeAdapter("adapter-3"))).isEmpty();
        assertThat(registry.size()).isEqualTo(3);

        assertThatThrownBy(() -> registry.replace("account-a", other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapterId 重复");
        assertThat(registry.unregister("account-a")).containsSame(second);
        assertThat(registry.unregister("account-a")).isEmpty();
        assertThat(registry.find("account-a")).isEmpty();
        assertThat(registry.size()).isEqualTo(2);
    }

    private static final class FakeAdapter implements MessagingAdapter {
        private final String adapterId;

        private FakeAdapter(String adapterId) {
            this.adapterId = adapterId;
        }

        @Override public String adapterId() { return adapterId; }
        @Override public void start(InboundHandler handler) { }
        @Override public CompletionStage<SendReceipt> send(OutboundMessage message) {
            return CompletableFuture.completedFuture(SendReceipt.sent(List.of("m-1")));
        }
        @Override public AdapterStatus status() {
            return new AdapterStatus(AdapterStatus.ConnectionState.CONNECTED, Instant.now(),
                    null, null);
        }
        @Override public void addEventListener(AdapterEventListener listener) { }
        @Override public void removeEventListener(AdapterEventListener listener) { }
        @Override public void close() { }
    }
}
