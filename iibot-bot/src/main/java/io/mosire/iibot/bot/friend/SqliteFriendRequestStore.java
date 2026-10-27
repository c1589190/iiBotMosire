package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;

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

/** 与消息板共用 bot.db 的好友申请存储。 */
public final class SqliteFriendRequestStore implements FriendRequestStore, AutoCloseable {

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
                        CREATE TABLE IF NOT EXISTS friend_request (
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
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开好友申请存储失败", failure);
        }
    }

    @Override
    public synchronized StoredFriendRequest upsert(FriendRequestEvent event) {
        Objects.requireNonNull(event, "event");
        ensureOpen();
        String requestId = requestId(event.flag());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO friend_request (
                  request_id, flag, user_id, display_name, comment, received_at,
                  status, remark, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', '', NULL)
                ON CONFLICT(flag) DO UPDATE SET
                  user_id = excluded.user_id,
                  display_name = excluded.display_name,
                  comment = excluded.comment,
                  received_at = excluded.received_at
                """)) {
            statement.setString(1, requestId);
            statement.setString(2, event.flag());
            statement.setString(3, event.userId());
            statement.setString(4, event.nickname());
            statement.setString(5, event.comment());
            statement.setString(6, event.receivedAt().toString());
            statement.executeUpdate();
            return findInternal(requestId).orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException("保存好友申请失败", failure);
        }
    }

    @Override
    public synchronized Optional<StoredFriendRequest> find(String requestId) {
        ensureOpen();
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        try {
            return findInternal(requestId.trim());
        } catch (SQLException failure) {
            throw new IllegalStateException("读取好友申请失败", failure);
        }
    }

    @Override
    public synchronized List<FriendRequestRecord> listPending(int limit) {
        ensureOpen();
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1..200");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request_id, user_id, display_name, comment, received_at,
                       status, remark, decided_at
                FROM friend_request
                WHERE status = 'PENDING'
                ORDER BY received_at DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
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
    public synchronized void markDecided(String requestId, String status,
                                          String remark, Instant decidedAt) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE friend_request
                SET status = ?, remark = ?, decided_at = ?
                WHERE request_id = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, remark == null ? "" : remark);
            statement.setString(3, decidedAt.toString());
            statement.setString(4, requestId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新好友申请失败", failure);
        }
    }

    private Optional<StoredFriendRequest> findInternal(String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request_id, flag, user_id, display_name, comment, received_at,
                       status, remark, decided_at
                FROM friend_request WHERE request_id = ?
                """)) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                FriendRequestRecord request = map(result);
                return Optional.of(new StoredFriendRequest(request, result.getString("flag")));
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

    private static String requestId(String flag) {
        try {
            return "fr_" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(flag.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
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
