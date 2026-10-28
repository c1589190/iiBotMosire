package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteFriendRequestStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void persistsFlagLocallyButHidesItFromRecord() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteFriendRequestStore store = new SqliteFriendRequestStore(db)) {
            StoredFriendRequest stored = store.upsert("bot-1", new FriendRequestEvent(
                    "raw-flag", "qq-2", "Bob", "hello", T0));

            assertThat(stored.flag()).isEqualTo("raw-flag");
            assertThat(stored.request().requestId()).doesNotContain("raw-flag");
            assertThat(store.listPending("bot-1", 10)).singleElement()
                    .extracting(FriendRequestRecord::requestId)
                    .isEqualTo(stored.request().requestId());
            store.markDecided("bot-1", stored.request().requestId(), "APPROVED", "备注", T0);
            assertThat(store.listPending("bot-1", 10)).isEmpty();
        }
        try (SqliteFriendRequestStore reopened = new SqliteFriendRequestStore(db)) {
            assertThat(reopened.find("bot-1", "missing")).isEmpty();
        }
    }

    @Test
    void sameFlagAndUserOnTwoAccountsDoesNotOverwrite() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteFriendRequestStore store = new SqliteFriendRequestStore(db)) {
            StoredFriendRequest a = store.upsert("bot-a", new FriendRequestEvent(
                    "flag-shared", "qq-9", "Carol", "来自 A", T0));
            StoredFriendRequest b = store.upsert("bot-b", new FriendRequestEvent(
                    "flag-shared", "qq-9", "Carol", "来自 B", T0));

            assertThat(a.request().requestId()).isNotEqualTo(b.request().requestId());
            assertThat(a.request().comment()).isEqualTo("来自 A");
            assertThat(b.request().comment()).isEqualTo("来自 B");
            assertThat(store.listPending("bot-a", 10)).extracting(FriendRequestRecord::comment)
                    .containsExactly("来自 A");
            assertThat(store.listPending("bot-b", 10)).extracting(FriendRequestRecord::comment)
                    .containsExactly("来自 B");

            store.markDecided("bot-a", a.request().requestId(), "APPROVED", "A", T0);
            assertThat(store.listPending("bot-a", 10)).isEmpty();
            assertThat(store.listPending("bot-b", 10)).hasSize(1);
            assertThat(store.find("bot-b", a.request().requestId())).isEmpty();
            assertThat(store.find("bot-a", a.request().requestId())).get()
                    .extracting(stored -> stored.request().status())
                    .isEqualTo("APPROVED");
        }
    }

    @Test
    void migratesLegacySingleAccountRowsToDefaultWithoutDroppingOldTable() throws Exception {
        Path db = tempDir.resolve("bot.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE friend_request (
                      request_id TEXT PRIMARY KEY,
                      flag TEXT NOT NULL UNIQUE,
                      user_id TEXT NOT NULL,
                      display_name TEXT NOT NULL,
                      comment TEXT NOT NULL,
                      received_at TEXT NOT NULL,
                      status TEXT NOT NULL,
                      remark TEXT NOT NULL,
                      decided_at TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO friend_request (
                      request_id, flag, user_id, display_name, comment, received_at,
                      status, remark, decided_at
                    ) VALUES ('fr_legacy', 'legacy-flag', 'qq-5', 'Eve', '旧数据',
                              '2026-10-26T00:00:00Z', 'PENDING', '', NULL)
                    """);
        }

        try (SqliteFriendRequestStore store = new SqliteFriendRequestStore(db)) {
            assertThat(store.listPending(SqliteFriendRequestStore.LEGACY_ACCOUNT_ID, 10))
                    .singleElement()
                    .satisfies(request -> {
                        assertThat(request.requestId()).isEqualTo("fr_legacy");
                        assertThat(request.userId()).isEqualTo("qq-5");
                        assertThat(request.comment()).isEqualTo("旧数据");
                    });
            assertThat(store.listPending("bot-a", 10)).isEmpty();
            store.markDecided(SqliteFriendRequestStore.LEGACY_ACCOUNT_ID, "fr_legacy",
                    "APPROVED", "旧备注", T0);
        }

        // 重新打开不重复迁移、不把已审批记录恢复成 PENDING，旧表仍保留。
        try (SqliteFriendRequestStore reopened = new SqliteFriendRequestStore(db)) {
            assertThat(reopened.listPending(SqliteFriendRequestStore.LEGACY_ACCOUNT_ID, 10))
                    .isEmpty();
            assertThat(reopened.find(SqliteFriendRequestStore.LEGACY_ACCOUNT_ID, "fr_legacy"))
                    .get().extracting(stored -> stored.request().status())
                    .isEqualTo("APPROVED");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
                statement.setString(1, "friend_request");
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
        }
    }
}
