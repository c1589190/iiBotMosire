package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;
import io.mosire.iibot.connect.ops.OpsLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 与消息板共用 bot.db 的逐账号好友申请存储。
 *
 * <p>业务表为 {@code friend_request_v2}，主键 {@code (account_id, request_id)}、
 * 唯一键 {@code (account_id, flag)}；同一 OneBot flag 出现在两个账号下不会互相覆盖。
 * 旧表 {@code friend_request} 若存在，其数据一次性迁移为 {@code account_id='default'}，
 * 旧表原样保留不删除，迁移用 {@code INSERT OR IGNORE} 保证可重复执行。</p>
 */
public final class SqliteFriendRequestStore implements FriendRequestStore, AutoCloseable {

    /** 旧单账号数据的迁移归属账号。 */
    public static final String LEGACY_ACCOUNT_ID = "default";

    private static final String TABLE = "friend_request_v2";
    private static final String LEGACY_TABLE = "friend_request";

    private final Connection connection;
    private boolean closed;

    public SqliteFriendRequestStore(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS friend_request_v2 (
                          account_id TEXT NOT NULL,
                          request_id TEXT NOT NULL,
                          flag TEXT NOT NULL,
                          user_id TEXT NOT NULL,
                          display_name TEXT NOT NULL,
                          comment TEXT NOT NULL,
                          received_at TEXT NOT NULL,
                          status TEXT NOT NULL,
                          remark TEXT NOT NULL,
                          decided_at TEXT,
                          PRIMARY KEY (account_id, request_id),
                          UNIQUE (account_id, flag)
                        )
                        """);
            }
            migrateLegacy();
        } catch (Exception failure) {
            throw new IllegalStateException("打开好友申请存储失败", failure);
        }
    }

    @Override
    public synchronized StoredFriendRequest upsert(String accountId, FriendRequestEvent event) {
        Objects.requireNonNull(event, "event");
        ensureOpen();
        String account = requireAccountId(accountId);
        String requestId = requestId(account, event.flag());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO friend_request_v2 (
                  account_id, request_id, flag, user_id, display_name, comment,
                  received_at, status, remark, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', '', NULL)
                ON CONFLICT(account_id, flag) DO UPDATE SET
                  user_id = excluded.user_id,
                  display_name = excluded.display_name,
                  comment = excluded.comment,
                  received_at = excluded.received_at
                """)) {
            statement.setString(1, account);
            statement.setString(2, requestId);
            statement.setString(3, event.flag());
            statement.setString(4, event.userId());
            statement.setString(5, event.nickname());
            statement.setString(6, event.comment());
            statement.setString(7, event.receivedAt().toString());
            statement.executeUpdate();
            return findInternal(account, requestId).orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException("保存好友申请失败", failure);
        }
    }

    @Override
    public synchronized Optional<StoredFriendRequest> find(String accountId, String requestId) {
        ensureOpen();
        if (accountId == null || accountId.isBlank()
                || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        try {
            return findInternal(accountId.trim(), requestId.trim());
        } catch (SQLException failure) {
            throw new IllegalStateException("读取好友申请失败", failure);
        }
    }

    @Override
    public synchronized List<FriendRequestRecord> listPending(String accountId, int limit) {
        ensureOpen();
        String account = requireAccountId(accountId);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1..200");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request_id, user_id, display_name, comment, received_at,
                       status, remark, decided_at
                FROM friend_request_v2
                WHERE account_id = ? AND status = 'PENDING'
                ORDER BY received_at DESC
                LIMIT ?
                """)) {
            statement.setString(1, account);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<FriendRequestRecord> requests = new ArrayList<>();
                while (result.next()) {
                    requests.add(map(result));
                }
                return List.copyOf(requests);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("列出好友申请失败", failure);
        }
    }

    @Override
    public synchronized void markDecided(String accountId, String requestId, String status,
                                          String remark, Instant decidedAt) {
        ensureOpen();
        String account = requireAccountId(accountId);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE friend_request_v2
                SET status = ?, remark = ?, decided_at = ?
                WHERE account_id = ? AND request_id = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, remark == null ? "" : remark);
            statement.setString(3, decidedAt.toString());
            statement.setString(4, account);
            statement.setString(5, requestId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新好友申请失败", failure);
        }
    }

    /**
     * 把旧 {@code friend_request} 数据迁移到 {@code friend_request_v2}。
     *
     * <p>旧表不删除；重复启动使用 {@code INSERT OR IGNORE}，已存在同主键行时保留 v2 现状，
     * 因此不会把已审批结果重置为旧的 PENDING。</p>
     */
    private void migrateLegacy() throws SQLException {
        if (!tableExists(LEGACY_TABLE)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            int migrated = statement.executeUpdate("""
                    INSERT OR IGNORE INTO friend_request_v2 (
                      account_id, request_id, flag, user_id, display_name, comment,
                      received_at, status, remark, decided_at
                    )
                    SELECT '%s', request_id, flag, user_id, display_name, comment,
                           received_at, status, remark, decided_at
                    FROM friend_request
                    """.formatted(LEGACY_ACCOUNT_ID));
            if (migrated > 0) {
                OpsLog.info("event=friend_request_migrated legacyTable={} targetTable={} "
                                + "accountId={} rows={}", LEGACY_TABLE, TABLE,
                        LEGACY_ACCOUNT_ID, migrated);
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Optional<StoredFriendRequest> findInternal(String accountId, String requestId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request_id, flag, user_id, display_name, comment, received_at,
                       status, remark, decided_at
                FROM friend_request_v2 WHERE account_id = ? AND request_id = ?
                """)) {
            statement.setString(1, accountId);
            statement.setString(2, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredFriendRequest(map(result),
                        result.getString("flag")));
            }
        }
    }

    private static FriendRequestRecord map(ResultSet result) throws SQLException {
        String decided = result.getString("decided_at");
        return new FriendRequestRecord(
                result.getString("request_id"),
                result.getString("user_id"),
                result.getString("display_name"),
                result.getString("comment"),
                Instant.parse(result.getString("received_at")),
                result.getString("status"),
                result.getString("remark"),
                decided == null ? null : Instant.parse(decided));
    }

    /** requestId 是 (accountId, flag) 的稳定摘要，不含 raw flag，也不同账号可区分。 */
    private static String requestId(String accountId, String flag) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (accountId + "\u0000" + flag).getBytes(StandardCharsets.UTF_8));
            return "fr_" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static String requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        return accountId.trim();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("好友申请存储已关闭");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("关闭好友申请存储失败", failure);
        }
    }
}
