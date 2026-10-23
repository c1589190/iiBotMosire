package io.mosire.iibot.app.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonDeliveryLedgerTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileIsEmptyAndClaimsRoundTripAcrossInstances() throws Exception {
        Path file = tempDir.resolve("delivery-ledger.json");
        JsonDeliveryLedger first = JsonDeliveryLedger.atFile(file);
        assertThat(first.load()).isEmpty();
        assertThat(first.claim("turn:turn-1:public")).isTrue();
        assertThat(first.claim("turn:turn-1:public")).isFalse();
        assertThat(first.claim("turn:turn-1:player:player-1")).isTrue();

        JsonDeliveryLedger restarted = JsonDeliveryLedger.atFile(file);
        assertThat(restarted.load())
                .containsExactlyInAnyOrder(
                        "turn:turn-1:public", "turn:turn-1:player:player-1");
        assertThat(restarted.claim("turn:turn-1:public")).isFalse();
        assertThat(restarted.claim("turn:turn-1:player:player-1")).isFalse();

        String json = Files.readString(file);
        assertThat(json).contains("\"schemaVersion\":1",
                        "turn:turn-1:public", "turn:turn-1:player:player-1")
                .doesNotContain("港口恢复了平静", "qq-p1", "token");
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void corruptUnknownSchemaUnknownFieldAndTrailingTokensFailClosed() throws Exception {
        Path corrupt = tempDir.resolve("corrupt.json");
        Files.writeString(corrupt, "{not-json");
        assertLedgerFailure(corrupt, DeliveryLedgerException.Code.CORRUPT);

        Path unknownSchema = tempDir.resolve("schema.json");
        Files.writeString(unknownSchema, """
                {"schemaVersion":2,"keys":[]}
                """);
        assertLedgerFailure(unknownSchema, DeliveryLedgerException.Code.SCHEMA);

        Path unknownField = tempDir.resolve("unknown.json");
        Files.writeString(unknownField, """
                {"schemaVersion":1,"keys":[],"unexpected":true}
                """);
        assertLedgerFailure(unknownField, DeliveryLedgerException.Code.CORRUPT);

        Path trailing = tempDir.resolve("trailing.json");
        Files.writeString(trailing, """
                {"schemaVersion":1,"keys":[]} {"schemaVersion":1,"keys":[]}
                """);
        assertLedgerFailure(trailing, DeliveryLedgerException.Code.CORRUPT);
    }

    @Test
    void concurrentClaimsFromMultipleInstancesAllowOnlyOneWinner() throws Exception {
        Path file = tempDir.resolve("delivery-ledger.json");
        JsonDeliveryLedger first = JsonDeliveryLedger.atFile(file);
        JsonDeliveryLedger second = JsonDeliveryLedger.atFile(file);
        int attempts = 32;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                JsonDeliveryLedger ledger = i % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> {
                    start.await();
                    return ledger.claim("turn:concurrent:public");
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(4, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(JsonDeliveryLedger.atFile(file).load())
                .containsExactly("turn:concurrent:public");
    }

    @Test
    void saveFailureLeavesOldFileIntactAndDoesNotReplaceMemoryState() throws Exception {
        Path file = tempDir.resolve("delivery-ledger.json");
        JsonDeliveryLedger ledger = JsonDeliveryLedger.atFile(file);
        assertThat(ledger.claim("turn:old:public")).isTrue();
        byte[] before = Files.readAllBytes(file);

        Set<java.nio.file.attribute.PosixFilePermission> original =
                Files.getPosixFilePermissions(tempDir);
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(() -> ledger.claim("turn:new:player:player-1"))
                    .isInstanceOf(DeliveryLedgerException.class)
                    .satisfies(failure -> assertThat(((DeliveryLedgerException) failure).code())
                            .isEqualTo(DeliveryLedgerException.Code.SAVE));
        } finally {
            Files.setPosixFilePermissions(tempDir, original);
        }

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(ledger.load()).containsExactly("turn:old:public");
        assertThat(ledger.claim("turn:new:player:player-1")).isTrue();
        assertThat(JsonDeliveryLedger.atFile(file).load())
                .containsExactlyInAnyOrder("turn:old:public", "turn:new:player:player-1");
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".delivery-ledger.json."));
        }
    }

    private static void assertLedgerFailure(Path file, DeliveryLedgerException.Code code) {
        assertThatThrownBy(() -> JsonDeliveryLedger.atFile(file))
                .isInstanceOf(DeliveryLedgerException.class)
                .satisfies(failure -> assertThat(((DeliveryLedgerException) failure).code())
                        .isEqualTo(code));
    }
}
