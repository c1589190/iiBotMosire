package io.mosire.iibot.bot.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite {@code consumer_thread_state} 游标表。
 *
 * <p>可与消息板共用 bot.db，但打开独立连接；表迁移使用
 * {@code CREATE TABLE IF NOT EXISTS}，可重复执行。</p>
 */
public final class SqliteConsumerConversationStore
        implements ConsumerConversationStore, AutoCloseable {

    private final Connection connection;
    private boolean closed;

    public SqliteConsumerConversationStore(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS consumer_thread_state (
                          consumer_id TEXT NOT NULL,
                          thread_id TEXT NOT NULL,
                          read_seq INTEGER NOT NULL,
                          delivered_seq INTEGER NOT NULL,
                          updated_at TEXT NOT NULL,
                          PRIMARY KEY (consumer_id, thread_id)
                        )
                        """);
                backfillLegacyDigestCursor(statement);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite consumer 游标失败", failure);
        }
    }

    @Override
    public synchronized Optional<ConsumerConversationState> find(
            String consumerId, String threadId) {
        ensureOpen();
        if (consumerId == null || consumerId.isBlank()
                || threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        try {
            return findInternal(consumerId.trim(), threadId.trim());
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite consumer 游标失败", failure);
        }
    }

    @Override
    public synchronized ConsumerConversationState recordDelivered(
            String consumerId, String threadId, long deliveredSeq, Instant updatedAt) {
        ensureOpen();
        String normalizedConsumer = requireText(consumerId, "consumerId");
        String normalizedThread = requireText(threadId, "threadId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (deliveredSeq < 0L) {
            throw new IllegalArgumentException("deliveredSeq 不能为负");
        }
        try {
            Optional<ConsumerConversationState> current = findInternal(
                    normalizedConsumer, normalizedThread);
            if (current.isPresent()) {
                ConsumerConversationState state = current.orElseThrow();
                if (deliveredSeq <= state.deliveredSeq()) {
                    return state;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE consumer_thread_state
                        SET delivered_seq = ?, updated_at = ?
                        WHERE consumer_id = ? AND thread_id = ?
                          AND delivered_seq < ?
                        """)) {
                    statement.setLong(1, deliveredSeq);
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, normalizedConsumer);
                    statement.setString(4, normalizedThread);
                    statement.setLong(5, deliveredSeq);
                    statement.executeUpdate();
                }
                return findInternal(normalizedConsumer, normalizedThread).orElseThrow();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO consumer_thread_state (
                      consumer_id, thread_id, read_seq, delivered_seq, updated_at
                    ) VALUES (?, ?, 0, ?, ?)
                    ON CONFLICT(consumer_id, thread_id) DO UPDATE SET
                      delivered_seq = MAX(consumer_thread_state.delivered_seq,
                                          excluded.delivered_seq),
                      updated_at = CASE
                        WHEN excluded.delivered_seq > consumer_thread_state.delivered_seq
                        THEN excluded.updated_at
                        ELSE consumer_thread_state.updated_at
                      END
                    """)) {
                statement.setString(1, normalizedConsumer);
                statement.setString(2, normalizedThread);
                statement.setLong(3, deliveredSeq);
                statement.setString(4, updatedAt.toString());
                statement.executeUpdate();
            }
            return findInternal(normalizedConsumer, normalizedThread).orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 SQLite consumer deliveredSeq 失败", failure);
        }
    }

    @Override
    public synchronized ConsumerConversationState advanceRead(
            String consumerId, String threadId, long readSeq, Instant updatedAt) {
        ensureOpen();
        String normalizedConsumer = requireText(consumerId, "consumerId");
        String normalizedThread = requireText(threadId, "threadId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (readSeq < 0L) {
            throw new IllegalArgumentException("readSeq 不能为负");
        }
        try {
            ConsumerConversationState current = findInternal(
                    normalizedConsumer, normalizedThread).orElseThrow(() ->
                    new IllegalStateException("尚未记录 deliveredSeq，不能推进 readSeq"));
            if (readSeq <= current.readSeq()) {
                return current;
            }
            if (readSeq > current.deliveredSeq()) {
                throw new IllegalArgumentException("readSeq 不能超过 deliveredSeq");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE consumer_thread_state
                    SET read_seq = ?, updated_at = ?
                    WHERE consumer_id = ? AND thread_id = ?
                      AND read_seq < ? AND delivered_seq >= ?
                    """)) {
                statement.setLong(1, readSeq);
                statement.setString(2, updatedAt.toString());
                statement.setString(3, normalizedConsumer);
                statement.setString(4, normalizedThread);
                statement.setLong(5, readSeq);
                statement.setLong(6, readSeq);
                statement.executeUpdate();
            }
            return findInternal(normalizedConsumer, normalizedThread).orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 SQLite consumer readSeq 失败", failure);
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
            throw new IllegalStateException("关闭 SQLite consumer 游标失败", failure);
        }
    }

    /**
     * P5 之前只有 inbox_digest_state；新 consumer 表为空时从已推进的 digest 游标
     * 一次性回填，避免旧库升级后第一条 wake 从 seq 0 重放历史。
     */
    private void backfillLegacyDigestCursor(Statement ddl) throws SQLException {
        try (ResultSet table = ddl.executeQuery("""
                SELECT 1 FROM sqlite_master
                WHERE type = 'table' AND name = 'inbox_digest_state'
                """)) {
            if (!table.next()) {
                return;
            }
        }
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO consumer_thread_state (
                  consumer_id, thread_id, read_seq, delivered_seq, updated_at
                )
                SELECT ?, s.thread_id, s.last_seq, s.last_seq, ?
                FROM inbox_digest_state s
                WHERE s.last_seq > 0
                  AND NOT EXISTS (
                    SELECT 1 FROM consumer_thread_state c
                    WHERE c.consumer_id = ? AND c.thread_id = s.thread_id
                  )
                """)) {
            statement.setString(1, DEFAULT_CONSUMER_ID);
            statement.setString(2, now);
            statement.setString(3, DEFAULT_CONSUMER_ID);
            statement.executeUpdate();
        }
    }

    private Optional<ConsumerConversationState> findInternal(
            String consumerId, String threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT consumer_id, thread_id, read_seq, delivered_seq, updated_at
                FROM consumer_thread_state
                WHERE consumer_id = ? AND thread_id = ?
                """)) {
            statement.setString(1, consumerId);
            statement.setString(2, threadId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ConsumerConversationState(
                        result.getString("consumer_id"),
                        result.getString("thread_id"),
                        result.getLong("read_seq"),
                        result.getLong("delivered_seq"),
                        Instant.parse(result.getString("updated_at"))));
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SQLite consumer 游标已关闭");
        }
    }
}
