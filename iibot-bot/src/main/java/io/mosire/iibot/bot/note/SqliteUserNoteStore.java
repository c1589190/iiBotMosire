package io.mosire.iibot.bot.note;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite 用户备注存储。
 *
 * <p>一个账号下的 QQ 用户只有一条备注；标签以排序后的换行分隔文本保存，
 * 因为标签本身禁止换行。{@code revision} 用于主 Agent 的乐观更新。</p>
 */
public final class SqliteUserNoteStore implements UserNoteStore, AutoCloseable {

    private final Connection connection;
    private final Clock clock;
    private boolean closed;

    public SqliteUserNoteStore(Path databaseFile, Clock clock) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        this.clock = Objects.requireNonNull(clock, "clock");
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
                        CREATE TABLE IF NOT EXISTS user_note (
                          account_id TEXT NOT NULL,
                          qq_id TEXT NOT NULL,
                          display_name TEXT NOT NULL,
                          tags_text TEXT NOT NULL,
                          note TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          revision INTEGER NOT NULL,
                          updated_at TEXT NOT NULL,
                          PRIMARY KEY(account_id, qq_id)
                        )
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite 用户备注存储失败", failure);
        }
    }

    @Override
    public synchronized Optional<UserNote> find(String accountId, String qqId) {
        ensureOpen();
        if (accountId == null || accountId.isBlank() || qqId == null || qqId.isBlank()) {
            return Optional.empty();
        }
        try {
            return findInternal(accountId.trim(), qqId.trim());
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 用户备注失败", failure);
        }
    }

    @Override
    public synchronized List<UserNote> list(int limit) {
        ensureOpen();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, qq_id, display_name, tags_text, note, priority,
                       revision, updated_at
                FROM user_note
                ORDER BY priority DESC, updated_at DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<UserNote> notes = new ArrayList<>();
                while (result.next()) {
                    notes.add(map(result));
                }
                return List.copyOf(notes);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 用户备注列表失败", failure);
        }
    }

    @Override
    public synchronized UserNote upsert(UserNoteDraft draft, Long expectedRevision) {
        ensureOpen();
        Objects.requireNonNull(draft, "draft");
        try {
            connection.setAutoCommit(false);
            Optional<UserNote> current = findInternal(draft.accountId(), draft.qqId());
            long currentRevision = current.map(UserNote::revision).orElse(0L);
            if (expectedRevision != null) {
                if (expectedRevision < 0L) {
                    throw new IllegalArgumentException("expectedRevision 不能为负");
                }
                if (expectedRevision.longValue() != currentRevision) {
                    throw new UserNoteRevisionConflictException(expectedRevision, currentRevision);
                }
            }
            long nextRevision = currentRevision + 1L;
            Instant updatedAt = clock.instant();
            if (current.isPresent()) {
                update(draft, nextRevision, updatedAt);
            } else {
                insert(draft, nextRevision, updatedAt);
            }
            UserNote saved = new UserNote(draft.accountId(), draft.qqId(),
                    draft.displayName(), draft.tags(), draft.note(), draft.priority(),
                    nextRevision, updatedAt);
            connection.commit();
            return saved;
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("写入 SQLite 用户备注失败", failure);
        } catch (RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            resetAutoCommit();
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
            throw new IllegalStateException("关闭 SQLite 用户备注存储失败", failure);
        }
    }

    private Optional<UserNote> findInternal(String accountId, String qqId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, qq_id, display_name, tags_text, note, priority,
                       revision, updated_at
                FROM user_note
                WHERE account_id = ? AND qq_id = ?
                """)) {
            statement.setString(1, accountId);
            statement.setString(2, qqId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private void insert(UserNoteDraft draft, long revision, Instant updatedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_note (
                  account_id, qq_id, display_name, tags_text, note, priority,
                  revision, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, draft, revision, updatedAt);
            statement.executeUpdate();
        }
    }

    private void update(UserNoteDraft draft, long revision, Instant updatedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE user_note
                SET display_name = ?, tags_text = ?, note = ?, priority = ?,
                    revision = ?, updated_at = ?
                WHERE account_id = ? AND qq_id = ?
                """)) {
            statement.setString(1, draft.displayName());
            statement.setString(2, tagsText(draft.tags()));
            statement.setString(3, draft.note());
            statement.setInt(4, draft.priority());
            statement.setLong(5, revision);
            statement.setString(6, updatedAt.toString());
            statement.setString(7, draft.accountId());
            statement.setString(8, draft.qqId());
            statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, UserNoteDraft draft, long revision,
                      Instant updatedAt) throws SQLException {
        statement.setString(1, draft.accountId());
        statement.setString(2, draft.qqId());
        statement.setString(3, draft.displayName());
        statement.setString(4, tagsText(draft.tags()));
        statement.setString(5, draft.note());
        statement.setInt(6, draft.priority());
        statement.setLong(7, revision);
        statement.setString(8, updatedAt.toString());
    }

    private static UserNote map(ResultSet result) throws SQLException {
        return new UserNote(
                result.getString("account_id"),
                result.getString("qq_id"),
                result.getString("display_name"),
                tags(result.getString("tags_text")),
                result.getString("note"),
                result.getInt("priority"),
                result.getLong("revision"),
                Instant.parse(result.getString("updated_at")));
    }

    private static String tagsText(Set<String> tags) {
        return String.join("\n", tags.stream().sorted().toList());
    }

    private static Set<String> tags(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(List.of(text.split("\\n", -1))));
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始写入异常。
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接不可用时由下一次操作显式报错。
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SQLite 用户备注存储已关闭");
        }
    }
}
