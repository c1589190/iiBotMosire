package io.mosire.iibot.bot.inbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/** SQLite digest 游标；与消息板共用 bot.db 的不同表。 */
public final class SqliteInboxDigestStateStore implements InboxDigestStateStore, AutoCloseable {

    private final Connection connection;
    private boolean closed;

    public SqliteInboxDigestStateStore(Path databaseFile) {
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
                        CREATE TABLE IF NOT EXISTS inbox_digest_state (
                          thread_id TEXT PRIMARY KEY,
                          item_id TEXT NOT NULL,
                          revision INTEGER NOT NULL,
                          last_seq INTEGER NOT NULL
                        )
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite digest 游标失败", failure);
        }
    }

    @Override
    public synchronized Optional<InboxDigestState> find(String threadId) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT thread_id, item_id, revision, last_seq
                FROM inbox_digest_state
                WHERE thread_id = ?
                """)) {
            statement.setString(1, threadId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new InboxDigestState(
                        result.getString("thread_id"),
                        result.getString("item_id"),
                        result.getLong("revision"),
                        result.getLong("last_seq")));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite digest 游标失败", failure);
        }
    }

    @Override
    public synchronized void save(InboxDigestState state) {
        ensureOpen();
        Objects.requireNonNull(state, "state");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inbox_digest_state (thread_id, item_id, revision, last_seq)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(thread_id) DO UPDATE SET
                  item_id = excluded.item_id,
                  revision = excluded.revision,
                  last_seq = excluded.last_seq
                """)) {
            statement.setString(1, state.threadId());
            statement.setString(2, state.itemId());
            statement.setLong(3, state.revision());
            statement.setLong(4, state.lastSeq());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 SQLite digest 游标失败", failure);
        }
    }

    @Override
    public synchronized void clear(String threadId) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM inbox_digest_state WHERE thread_id = ?")) {
            statement.setString(1, threadId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("清理 SQLite digest 游标失败", failure);
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
            throw new IllegalStateException("关闭 SQLite digest 游标失败", failure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SQLite digest 游标已关闭");
        }
    }
}
