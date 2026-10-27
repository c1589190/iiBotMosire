package io.mosire.iibot.bot.inbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 与 bot.db 共库的 Inbox digest 审计存储。 */
public final class SqliteInboxDigestAuditStore implements InboxDigestAuditStore, AutoCloseable {

    private final Connection connection;
    private boolean closed;

    public SqliteInboxDigestAuditStore(Path databaseFile) {
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
                        CREATE TABLE IF NOT EXISTS inbox_digest_audit (
                          item_id TEXT PRIMARY KEY,
                          thread_id TEXT NOT NULL,
                          from_seq INTEGER NOT NULL,
                          to_seq INTEGER NOT NULL,
                          summary TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          status TEXT NOT NULL,
                          revision INTEGER NOT NULL,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL,
                          acked_at TEXT
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_inbox_digest_audit_thread
                        ON inbox_digest_audit(thread_id, updated_at)
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开 Inbox 审计存储失败", failure);
        }
    }

    @Override
    public synchronized void upsert(InboxDigestAudit audit) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inbox_digest_audit (
                  item_id, thread_id, from_seq, to_seq, summary, priority, status,
                  revision, created_at, updated_at, acked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET
                  thread_id = excluded.thread_id,
                  from_seq = excluded.from_seq,
                  to_seq = excluded.to_seq,
                  summary = excluded.summary,
                  priority = excluded.priority,
                  status = excluded.status,
                  revision = excluded.revision,
                  updated_at = excluded.updated_at,
                  acked_at = excluded.acked_at
                """)) {
            statement.setString(1, audit.itemId());
            statement.setString(2, audit.threadId());
            statement.setLong(3, audit.fromSeq());
            statement.setLong(4, audit.toSeq());
            statement.setString(5, audit.summary());
            statement.setInt(6, audit.priority());
            statement.setString(7, audit.status());
            statement.setLong(8, audit.revision());
            statement.setString(9, audit.createdAt().toString());
            statement.setString(10, audit.updatedAt().toString());
            statement.setString(11, audit.ackedAt() == null ? null : audit.ackedAt().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 Inbox 审计失败", failure);
        }
    }

    @Override
    public synchronized void markDone(String threadId, String itemId, Instant at) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE inbox_digest_audit
                SET status = 'done', updated_at = ?, acked_at = ?
                WHERE item_id = ? AND thread_id = ?
                """)) {
            statement.setString(1, at.toString());
            statement.setString(2, at.toString());
            statement.setString(3, itemId);
            statement.setString(4, threadId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 Inbox 审计失败", failure);
        }
    }

    @Override
    public synchronized List<InboxDigestAudit> list(String threadId, int limit) {
        ensureOpen();
        String sql = """
                SELECT item_id, thread_id, from_seq, to_seq, summary, priority, status,
                       revision, created_at, updated_at, acked_at
                FROM inbox_digest_audit
                """ + (threadId == null || threadId.isBlank() ? "" : " WHERE thread_id = ? ")
                + " ORDER BY updated_at DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (threadId != null && !threadId.isBlank()) {
                statement.setString(index++, threadId);
            }
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<InboxDigestAudit> audits = new ArrayList<>();
                while (result.next()) {
                    String acked = result.getString("acked_at");
                    audits.add(new InboxDigestAudit(
                            result.getString("thread_id"),
                            result.getString("item_id"),
                            result.getLong("from_seq"),
                            result.getLong("to_seq"),
                            result.getString("summary"),
                            result.getInt("priority"),
                            result.getString("status"),
                            result.getLong("revision"),
                            Instant.parse(result.getString("created_at")),
                            Instant.parse(result.getString("updated_at")),
                            acked == null ? null : Instant.parse(acked)));
                }
                return List.copyOf(audits);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox 审计失败", failure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Inbox 审计存储已关闭");
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
            throw new IllegalStateException("关闭 Inbox 审计存储失败", failure);
        }
    }
}
