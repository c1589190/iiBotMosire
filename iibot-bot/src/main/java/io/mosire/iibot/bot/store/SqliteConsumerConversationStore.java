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
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS consumer_state_migration (
                          version INTEGER PRIMARY KEY,
                          applied_at TEXT NOT NULL
                        )
                        """);
                reconcileFromDigest(statement);
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
     * P8 迁移修正：旧 consumer 表可能是按 inbox_digest_state.last_seq 播种的，
     * 而 active item 的 fromSeq 才定义未读起点。版本 2 一次性按 active item 的
     * fromSeq-1 回填/校正；没有 active item 时才使用 digest 的 lastSeq。
     *
     * <p>只执行一次，避免正常 read 后未 ack 的 delivered 批次被重启覆盖。</p>
     */
    private void reconcileFromDigest(Statement ddl) throws SQLException {
        try (ResultSet applied = ddl.executeQuery(
                "SELECT 1 FROM consumer_state_migration WHERE version = 2")) {
            if (applied.next()) {
                return;
            }
        }
        boolean hasDigest;
        try (ResultSet table = ddl.executeQuery("""
                SELECT 1 FROM sqlite_master
                WHERE type = 'table' AND name = 'inbox_digest_state'
                """)) {
            hasDigest = table.next();
        }
        String now = Instant.now().toString();
        if (!hasDigest) {
            // digest 表尚未创建时不写迁移标记；等 BotRuntime 完成建表后再次打开再校正。
            return;
        }
        boolean hasAudit;
        try (ResultSet table = ddl.executeQuery("""
                SELECT 1 FROM sqlite_master
                WHERE type = 'table' AND name = 'inbox_digest_audit'
                """)) {
            hasAudit = table.next();
        }
        String select = hasAudit
                ? """
                  SELECT s.thread_id, s.item_id, s.last_seq,
                         a.from_seq AS active_from_seq, a.status AS active_status
                  FROM inbox_digest_state s
                  LEFT JOIN inbox_digest_audit a
                    ON a.item_id = s.item_id AND a.thread_id = s.thread_id
                  """
                : """
                  SELECT thread_id, item_id, last_seq,
                         0 AS active_from_seq, NULL AS active_status
                  FROM inbox_digest_state
                  """;
        try (PreparedStatement read = connection.prepareStatement(select);
             ResultSet result = read.executeQuery();
             PreparedStatement write = connection.prepareStatement("""
                     INSERT INTO consumer_thread_state (
                       consumer_id, thread_id, read_seq, delivered_seq, updated_at
                     ) VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(consumer_id, thread_id) DO UPDATE SET
                       read_seq = excluded.read_seq,
                       delivered_seq = excluded.delivered_seq,
                       updated_at = excluded.updated_at
                     """)) {
            while (result.next()) {
                String threadId = result.getString("thread_id");
                String itemId = result.getString("item_id");
                long lastSeq = Math.max(0L, result.getLong("last_seq"));
                long fromSeq = result.getLong("active_from_seq");
                String status = result.getString("active_status");
                boolean activeItem = itemId != null && !itemId.isBlank()
                        && "available".equals(status) && fromSeq > 0L;
                long desired = activeItem ? Math.max(0L, fromSeq - 1L) : lastSeq;
                write.setString(1, DEFAULT_CONSUMER_ID);
                write.setString(2, threadId);
                write.setLong(3, desired);
                write.setLong(4, desired);
                write.setString(5, now);
                write.addBatch();
            }
            write.executeBatch();
        }
        markMigrationApplied(now);
    }

    private void markMigrationApplied(String now) throws SQLException {
        try (PreparedStatement mark = connection.prepareStatement("""
                INSERT OR IGNORE INTO consumer_state_migration(version, applied_at)
                VALUES (2, ?)
                """)) {
            mark.setString(1, now);
            mark.executeUpdate();
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
