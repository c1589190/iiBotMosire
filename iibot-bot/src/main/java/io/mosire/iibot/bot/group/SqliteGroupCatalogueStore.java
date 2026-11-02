package io.mosire.iibot.bot.group;

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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite {@code group_catalogue} 表。
 *
 * <p>可与消息板共用 bot.db，但打开独立连接；迁移使用 {@code CREATE TABLE IF NOT EXISTS}，
 * 可重复执行。写入以 group_id 为主键做 upsert，最近一次观察覆盖来源与群名。</p>
 */
public final class SqliteGroupCatalogueStore implements GroupCatalogueStore, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteGroupCatalogueStore.class);

    private final Connection connection;
    private boolean closed;

    public SqliteGroupCatalogueStore(Path databaseFile) {
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
                        CREATE TABLE IF NOT EXISTS group_catalogue (
                          group_id TEXT PRIMARY KEY,
                          group_name TEXT NOT NULL,
                          source TEXT NOT NULL,
                          first_seen_at TEXT NOT NULL,
                          last_seen_at TEXT NOT NULL
                        )
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite 群目录失败", failure);
        }
    }

    @Override
    public synchronized void upsert(GroupCatalogueEntry entry) {
        Objects.requireNonNull(entry, "entry");
        ensureOpen();
        String now = entry.lastSeenAt().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO group_catalogue (group_id, group_name, source, first_seen_at,
                  last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(group_id) DO UPDATE SET
                  group_name = excluded.group_name,
                  source = excluded.source,
                  last_seen_at = excluded.last_seen_at
                """)) {
            statement.setString(1, entry.groupId());
            statement.setString(2, entry.groupName());
            statement.setString(3, entry.source());
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入群目录失败", failure);
        }
    }

    @Override
    public void upsertAll(Collection<GroupCatalogueEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (GroupCatalogueEntry entry : entries) {
            if (entry != null) {
                upsert(entry);
            }
        }
    }

    @Override
    public synchronized List<GroupCatalogueEntry> search(String keyword, int limit) {
        ensureOpen();
        int capped = Math.max(1, limit);
        String needle = keyword == null ? "" : keyword.trim();
        String sql = needle.isEmpty()
                ? "SELECT group_id, group_name, source, last_seen_at FROM group_catalogue"
                        + " ORDER BY last_seen_at DESC LIMIT ?"
                : "SELECT group_id, group_name, source, last_seen_at FROM group_catalogue"
                        + " WHERE group_id LIKE ? OR group_name LIKE ?"
                        + " ORDER BY last_seen_at DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (needle.isEmpty()) {
                statement.setInt(1, capped);
            } else {
                String pattern = "%" + needle + "%";
                statement.setString(1, pattern);
                statement.setString(2, pattern);
                statement.setInt(3, capped);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<GroupCatalogueEntry> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(read(rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("检索群目录失败", failure);
        }
    }

    @Override
    public List<GroupCatalogueEntry> list(int limit) {
        return search("", limit);
    }

    @Override
    public synchronized Optional<GroupCatalogueEntry> get(String groupId) {
        ensureOpen();
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT group_id, group_name, source, last_seen_at FROM group_catalogue
                WHERE group_id = ?
                """)) {
            statement.setString(1, groupId.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取群目录失败", failure);
        }
    }

    private static GroupCatalogueEntry read(ResultSet rows) throws SQLException {
        return new GroupCatalogueEntry(
                rows.getString("group_id"),
                rows.getString("group_name"),
                rows.getString("source"),
                parseInstant(rows.getString("last_seen_at")));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return Instant.now();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("群目录存储已关闭");
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
            LOG.warn("关闭群目录连接失败: {}", failure.getMessage());
        }
    }
}
