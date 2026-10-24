package io.mosire.iibot.app.event.content;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * events.db 的幂等 schema 迁移。
 *
 * <p>旧 AgentLib 审计库只有 {@code events(seq, ts, type, agent, payload, correlation_id)}。
 * 迁移只追加新列/新表，不删除旧列；旧行的 {@code event_version=0}、
 * {@code content_missing=1}，因此可读但不允许被误当作完整事件回放。</p>
 */
final class EventSchemaMigrator {

    static final int SCHEMA_VERSION = 1;

    private static final String CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS events (
              seq            INTEGER PRIMARY KEY AUTOINCREMENT,
              ts             TEXT    NOT NULL,
              type           TEXT    NOT NULL,
              agent          TEXT    NOT NULL,
              payload        TEXT    NOT NULL DEFAULT '',
              correlation_id TEXT    NOT NULL DEFAULT '',
              turn_id        TEXT,
              event_version  INTEGER NOT NULL DEFAULT 0,
              visibility     TEXT    NOT NULL DEFAULT 'GM',
              content_hash   TEXT    NOT NULL DEFAULT '',
              payload_json   TEXT    NOT NULL DEFAULT '',
              content_missing INTEGER NOT NULL DEFAULT 1
            )
            """;

    private static final String CREATE_CONTENTS = """
            CREATE TABLE IF NOT EXISTS event_contents (
              seq          INTEGER PRIMARY KEY REFERENCES events(seq) ON DELETE CASCADE,
              content_type TEXT    NOT NULL,
              content_json TEXT,
              content_text TEXT,
              CHECK (
                (content_type = 'JSON' AND content_json IS NOT NULL AND content_text IS NULL)
                OR (content_type IN ('MARKDOWN', 'TEXT')
                    AND content_text IS NOT NULL AND content_json IS NULL)
              )
            )
            """;

    private EventSchemaMigrator() {
    }

    static void migrate(Connection connection) throws SQLException {
        int version = userVersion(connection);
        if (version > SCHEMA_VERSION) {
            throw new IllegalStateException("[EVENT_SCHEMA_UNSUPPORTED] events.db schemaVersion="
                    + version + " supported=" + SCHEMA_VERSION);
        }

        if (!tableExists(connection, "events")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_EVENTS);
            }
        } else {
            ensureLegacyEventsCompatible(connection);
            addColumnIfMissing(connection, "events", "turn_id", "TEXT");
            addColumnIfMissing(connection, "events", "event_version",
                    "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "events", "visibility",
                    "TEXT NOT NULL DEFAULT 'GM'");
            addColumnIfMissing(connection, "events", "content_hash",
                    "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(connection, "events", "payload_json",
                    "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(connection, "events", "content_missing",
                    "INTEGER NOT NULL DEFAULT 1");
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_CONTENTS);
            statement.execute("""
                    UPDATE events
                    SET payload_json = payload
                    WHERE payload_json IS NULL OR payload_json = ''
                    """);
            // 没有正文的行只可能是旧 AgentLib 审计事件或异常半成品：按最保守的
            // v0/GM/content_missing 读取，禁止把其误当成可回放的完整事件。
            statement.execute("""
                    UPDATE events
                    SET event_version = 0,
                        visibility = 'GM',
                        content_missing = 1
                    WHERE NOT EXISTS (
                      SELECT 1 FROM event_contents c WHERE c.seq = events.seq
                    )
                    """);
            statement.execute("""
                    UPDATE events
                    SET content_missing = 0
                    WHERE EXISTS (
                      SELECT 1 FROM event_contents c WHERE c.seq = events.seq
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_events_type_correlation_id_seq
                    ON events (type, correlation_id, seq)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_events_turn_id_seq
                    ON events (turn_id, seq)
                    """);
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }

        validateContentsSchema(connection);
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            if (!rs.next()) {
                throw new IllegalStateException("[EVENT_SCHEMA_VERSION_MISSING] 无法读取 user_version");
            }
            return rs.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureLegacyEventsCompatible(Connection connection) throws SQLException {
        Set<String> columns = columns(connection, "events");
        for (String required : Set.of("seq", "ts", "type", "agent", "payload", "correlation_id")) {
            if (!columns.contains(required)) {
                throw new IllegalStateException("[EVENT_SCHEMA_LEGACY_INCOMPATIBLE] events 缺少列 "
                        + required);
            }
        }
    }

    private static void validateContentsSchema(Connection connection) throws SQLException {
        Set<String> columns = columns(connection, "event_contents");
        for (String required : Set.of("seq", "content_type", "content_json", "content_text")) {
            if (!columns.contains(required)) {
                throw new IllegalStateException("[EVENT_SCHEMA_CONTENT_INCOMPATIBLE] "
                        + "event_contents 缺少列 " + required);
            }
        }
    }

    private static void addColumnIfMissing(Connection connection, String table,
                                           String column, String definition) throws SQLException {
        if (columns(connection, table).contains(column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return Set.copyOf(columns);
    }
}
