package io.mosire.iibot.app.event.content;

import io.mosire.iibot.taskflow.event.EventAppendPort;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventAppendRequest;
import io.mosire.iibot.taskflow.event.EventContent;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * events.db 完整事件存储：headers 与正文在同一 SQLite 事务中落库。
 *
 * <p>这是 P1 的内容基础，不替代 P2 的领域事件接入。新事件必须同时提供：
 * 元数据 JSON、内容类型、可见性、事件版本和唯一正文；正文 hash 在脱敏后计算并
 * 写入 {@code events.content_hash}。读取时会重算 hash，不一致即 fail-closed。</p>
 *
 * <p>旧 AgentLib 审计表通过 {@link EventSchemaMigrator} 幂等升级；旧行保留
 * {@code event_version=0/content_missing=1}，只读而不伪造正文。</p>
 */
public final class EventContentStore implements EventAppendPort, AutoCloseable {

    public static final int CURRENT_EVENT_VERSION = 1;

    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;

    private static final int MAX_EVENT_TYPE_BYTES = 128;
    private static final int MAX_AGENT_BYTES = 512;
    private static final int MAX_CORRELATION_BYTES = 512;
    private static final int MAX_TURN_ID_BYTES = 256;

    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private static final String INSERT_EVENT = """
            INSERT INTO events (
              ts, type, agent, payload, correlation_id,
              turn_id, event_version, visibility, content_hash, payload_json,
              content_missing
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """;

    private static final String INSERT_CONTENT = """
            INSERT INTO event_contents (seq, content_type, content_json, content_text)
            VALUES (?, ?, ?, ?)
            """;

    private static final String SELECT_EVENT = """
            SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id,
                   e.turn_id, e.event_version, e.visibility, e.content_hash,
                   e.payload_json, e.content_missing,
                   c.content_type, c.content_json, c.content_text
            FROM events e
            LEFT JOIN event_contents c ON c.seq = e.seq
            WHERE e.seq = ?
            """;

    private static final String SELECT_AFTER = """
            SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id,
                   e.turn_id, e.event_version, e.visibility, e.content_hash,
                   e.payload_json, e.content_missing,
                   c.content_type, c.content_json, c.content_text
            FROM events e
            LEFT JOIN event_contents c ON c.seq = e.seq
            WHERE e.seq > ?
            ORDER BY e.seq ASC
            LIMIT ?
            """;

    private static final String SELECT_LATEST_BY_TYPE = """
            SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id,
                   e.turn_id, e.event_version, e.visibility, e.content_hash,
                   e.payload_json, e.content_missing,
                   c.content_type, c.content_json, c.content_text
            FROM events e
            LEFT JOIN event_contents c ON c.seq = e.seq
            WHERE e.type = ?
            ORDER BY e.seq DESC
            LIMIT 1
            """;

    private static final String SELECT_LATEST_BY_TYPE_PREFIX = """
            SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id,
                   e.turn_id, e.event_version, e.visibility, e.content_hash,
                   e.payload_json, e.content_missing,
                   c.content_type, c.content_json, c.content_text
            FROM events e
            LEFT JOIN event_contents c ON c.seq = e.seq
            WHERE substr(e.type, 1, ?) = ?
            ORDER BY e.seq DESC
            LIMIT 1
            """;

    private static final String SELECT_BY_TYPE_PREFIX = """
            SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id,
                   e.turn_id, e.event_version, e.visibility, e.content_hash,
                   e.payload_json, e.content_missing,
                   c.content_type, c.content_json, c.content_text
            FROM events e
            LEFT JOIN event_contents c ON c.seq = e.seq
            WHERE substr(e.type, 1, ?) = ?
            ORDER BY e.seq ASC
            LIMIT ?
            """;

    private static final String SELECT_BY_TYPE_PREFIX_AFTER = """
            SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id,
                   e.turn_id, e.event_version, e.visibility, e.content_hash,
                   e.payload_json, e.content_missing,
                   c.content_type, c.content_json, c.content_text
            FROM events e
            LEFT JOIN event_contents c ON c.seq = e.seq
            WHERE substr(e.type, 1, ?) = ? AND e.seq > ?
            ORDER BY e.seq ASC
            LIMIT ?
            """;

    private final Path dbFile;
    private final Connection connection;
    private final EventContentSanitizer sanitizer;
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public EventContentStore(Path dbFile) {
        this(dbFile, List.of());
    }

    public EventContentStore(Path dbFile, Collection<String> secrets) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile")
                .toAbsolutePath().normalize();
        Path parent = this.dbFile.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("events.db 路径必须有父目录");
        }
        this.sanitizer = new EventContentSanitizer(secrets);

        Connection opened = null;
        try {
            Files.createDirectories(parent);
            opened = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
            // 连接建立后数据库文件已存在；先收口主文件权限，再执行可能失败/产生 WAL 的迁移。
            restrictFilePermissions(this.dbFile);
            configure(opened);
            EventSchemaMigrator.migrate(opened);
            restrictFilePermissions(this.dbFile);
            restrictCompanionFilePermissions(this.dbFile);
        } catch (SQLException | IOException e) {
            closeSilently(opened);
            throw new IllegalStateException("[EVENT_DB_OPEN] 打开完整事件库失败: " + this.dbFile, e);
        } catch (RuntimeException e) {
            closeSilently(opened);
            throw e;
        }
        this.connection = opened;
    }

    @Override
    public StoredEvent append(EventAppendRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.eventVersion() != CURRENT_EVENT_VERSION) {
            throw new IllegalArgumentException("[EVENT_VERSION_UNSUPPORTED] 新完整事件只允许"
                    + "当前版本 expected=" + CURRENT_EVENT_VERSION
                    + " actual=" + request.eventVersion());
        }
        ensureOpen();

        // 脱敏本身需要线性内存（覆盖掩码）；先对原始输入做上限检查，避免超大正文在
        // 脱敏阶段才爆炸。脱敏后仍再检查一次，覆盖秘密替换后文本变长的情况。
        requireUtf8AtMost("payloadJson", request.payloadJson(), MAX_PAYLOAD_BYTES);
        requireUtf8AtMost("content", request.content().body(), MAX_CONTENT_BYTES);
        requireUtf8AtMost("eventType", request.eventType(), MAX_EVENT_TYPE_BYTES);
        requireUtf8AtMost("agent", request.agent(), MAX_AGENT_BYTES);
        requireUtf8AtMost("correlationId", request.correlationId(), MAX_CORRELATION_BYTES);
        if (request.turnId() != null) {
            requireUtf8AtMost("turnId", request.turnId(), MAX_TURN_ID_BYTES);
        }

        String payloadJson = sanitizer.sanitizeJson(request.payloadJson(), true);
        EventContent content = sanitizeContent(request.content(), request.eventType());
        String eventType = sanitizer.sanitizeHeaderValue(request.eventType());
        String agent = sanitizer.sanitizeHeaderValue(request.agent());
        String correlationId = sanitizer.sanitizeHeaderValue(request.correlationId());
        String turnId = sanitizer.sanitizeHeaderValue(request.turnId());
        requireUtf8AtMost("payloadJson", payloadJson, MAX_PAYLOAD_BYTES);
        requireUtf8AtMost("content", content.body(), MAX_CONTENT_BYTES);
        requireUtf8AtMost("eventType", eventType, MAX_EVENT_TYPE_BYTES);
        requireUtf8AtMost("agent", agent, MAX_AGENT_BYTES);
        requireUtf8AtMost("correlationId", correlationId, MAX_CORRELATION_BYTES);
        if (turnId != null) {
            requireUtf8AtMost("turnId", turnId, MAX_TURN_ID_BYTES);
        }
        String contentHash = contentHash(content);

        synchronized (lock) {
            ensureOpen();
            try {
                connection.setAutoCommit(false);
                long seq;
                try (PreparedStatement statement = connection.prepareStatement(
                        INSERT_EVENT, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, request.occurredAt().toString());
                    statement.setString(2, eventType);
                    statement.setString(3, agent);
                    statement.setString(4, payloadJson);
                    statement.setString(5, correlationId);
                    setNullableString(statement, 6, turnId);
                    statement.setLong(7, request.eventVersion());
                    statement.setString(8, request.visibility().name());
                    statement.setString(9, contentHash);
                    statement.setString(10, payloadJson);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("事件插入未返回 seq");
                        }
                        seq = keys.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(INSERT_CONTENT)) {
                    statement.setLong(1, seq);
                    statement.setString(2, content.type().name());
                    setNullableString(statement, 3, content.json());
                    setNullableString(statement, 4, content.text());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("正文插入未返回一条变更");
                    }
                }
                connection.commit();
                restrictCompanionFilePermissions(dbFile);
                return new StoredEvent(seq, request.occurredAt(), eventType,
                        agent, correlationId, turnId,
                        request.eventVersion(), request.visibility(), contentHash, false,
                        payloadJson, content);
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("[EVENT_DB_APPEND] 完整事件追加失败 type="
                        + eventType, e);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // 下一次使用会再次失败并由 ensureOpen/上层事务边界暴露；不在 finally
                    // 里覆盖真正的追加异常。
                }
            }
        }
    }

    /** 按 seq 读取一条事件；不存在时为空。旧事件可能只有 headers。 */
    public Optional<StoredEvent> read(long seq) {
        if (seq <= 0) {
            throw new IllegalArgumentException("seq 必须为正");
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_EVENT)) {
                statement.setLong(1, seq);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("[EVENT_DB_READ] 读取完整事件失败 seq=" + seq, e);
            }
        }
    }

    /** 按 seq 升序读取；用于投影/迁移对账测试。 */
    public List<StoredEvent> readAfter(long afterSeq, int limit) {
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正");
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_AFTER)) {
                statement.setLong(1, afterSeq);
                statement.setInt(2, limit);
                List<StoredEvent> events = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        events.add(readRow(rs));
                    }
                }
                return List.copyOf(events);
            } catch (SQLException e) {
                throw new IllegalStateException("[EVENT_DB_READ] 读取完整事件失败 afterSeq="
                        + afterSeq, e);
            }
        }
    }

    /**
     * 读取指定事件类型的最后一条完整事件（按存储 seq 降序）。
     *
     * @param eventType 精确事件类型；不能为空白
     * @return 没有匹配事件时为空；内容缺失/hash 不一致时 fail-closed
     */
    public Optional<StoredEvent> latestByType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 不能为空白");
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT_LATEST_BY_TYPE)) {
                statement.setString(1, eventType);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "[EVENT_DB_READ] 读取最新完整事件失败 type=" + eventType, e);
            }
        }
    }

    /**
     * 读取指定前缀下最后一条完整事件（按存储 seq 降序）。
     *
     * <p>前缀按字面量匹配，不支持 SQL LIKE 通配符；用于按 {@code POST_} /
     * {@code COMPLAINT_} 等稳定命名空间恢复领域最新快照。</p>
     *
     * @param prefix 非空事件类型前缀
     * @return 没有匹配事件时为空；内容缺失/hash 不一致时 fail-closed
     */
    public Optional<StoredEvent> latestByTypePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("eventTypePrefix 不能为空白");
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT_LATEST_BY_TYPE_PREFIX)) {
                statement.setInt(1, prefix.length());
                statement.setString(2, prefix);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "[EVENT_DB_READ] 读取最新前缀事件失败 prefix=" + prefix, e);
            }
        }
    }

    /**
     * 按事件类型前缀读取有界完整事件（seq 升序）。用于命令日志重放。
     */
    public List<StoredEvent> readByTypePrefix(String prefix) {
        return readByTypePrefix(prefix, 10_000);
    }

    /**
     * 非分页兼容读取：最多 limit 条；若实际还有更多则 fail-closed，拒绝静默截断。
     */
    public List<StoredEvent> readByTypePrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("eventTypePrefix 不能为空白");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正");
        }
        List<StoredEvent> events = readByTypePrefix(prefix, 0L, limit + 1);
        if (events.size() > limit) {
            throw new IllegalStateException(
                    "[EVENT_DB_READ] 前缀事件超过 limit，拒绝静默截断 prefix="
                            + prefix + " limit=" + limit);
        }
        return events;
    }

    /**
     * 分页读取：seq 升序、exclusive afterSeq，最多 limit 条。
     */
    public List<StoredEvent> readByTypePrefix(String prefix, long afterSeq, int limit) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("eventTypePrefix 不能为空白");
        }
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正");
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT_BY_TYPE_PREFIX_AFTER)) {
                statement.setInt(1, prefix.length());
                statement.setString(2, prefix);
                statement.setLong(3, afterSeq);
                statement.setInt(4, limit);
                List<StoredEvent> events = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        events.add(readRow(rs));
                    }
                }
                return List.copyOf(events);
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "[EVENT_DB_READ] 分页读取前缀事件失败 prefix=" + prefix
                                + " afterSeq=" + afterSeq, e);
            }
        }
    }

    public int schemaVersion() {
        synchronized (lock) {
            ensureOpen();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
                return rs.next() ? rs.getInt(1) : -1;
            } catch (SQLException e) {
                throw new IllegalStateException("[EVENT_DB_SCHEMA] 读取 schemaVersion 失败", e);
            }
        }
    }

    public Path dbFile() {
        return dbFile;
    }

    private EventContent sanitizeContent(EventContent original, String eventType) {
        boolean losslessSnapshot = DomainEventTypes.TASKFLOW_SNAPSHOT.equals(eventType)
                || DomainEventTypes.TASKFLOW_SNAPSHOT_MIGRATED.equals(eventType);
        return switch (original.type()) {
            case JSON -> new EventContent(EventContentType.JSON,
                    sanitizer.sanitizeJson(original.json(), false, !losslessSnapshot), null);
            case MARKDOWN, TEXT -> new EventContent(original.type(), null,
                    sanitizer.sanitizeText(original.text()));
        };
    }

    private static StoredEvent readRow(ResultSet rs) throws SQLException {
        long seq = rs.getLong("seq");
        Instant occurredAt = Instant.parse(rs.getString("ts"));
        String eventType = rs.getString("type");
        String agent = rs.getString("agent");
        String correlationId = rs.getString("correlation_id");
        String turnId = rs.getString("turn_id");
        long eventVersion = rs.getLong("event_version");
        if (eventVersion < 0 || eventVersion > CURRENT_EVENT_VERSION) {
            throw new IllegalStateException("[EVENT_VERSION_UNSUPPORTED] seq=" + seq
                    + " eventVersion=" + eventVersion
                    + " supported=0.." + CURRENT_EVENT_VERSION);
        }
        EventVisibility visibility;
        try {
            visibility = EventVisibility.valueOf(rs.getString("visibility"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[EVENT_SCHEMA_VISIBILITY] 未知 visibility seq=" + seq);
        }
        String contentHash = rs.getString("content_hash");
        String payloadJson = rs.getString("payload_json");
        if (payloadJson == null || payloadJson.isEmpty()) {
            payloadJson = rs.getString("payload");
        }
        boolean contentMissing = rs.getInt("content_missing") != 0;
        String contentType = rs.getString("content_type");

        if (contentMissing) {
            if (contentType != null) {
                throw new IllegalStateException("[EVENT_CONTENT_FLAG_MISMATCH] 旧事件标记为缺正文"
                        + "但存在正文 seq=" + seq);
            }
            return new StoredEvent(seq, occurredAt, eventType, agent, correlationId, turnId,
                    eventVersion, visibility, contentHash, true, payloadJson, null);
        }

        if (eventVersion != CURRENT_EVENT_VERSION) {
            throw new IllegalStateException("[EVENT_VERSION_UNSUPPORTED] 完整事件版本不匹配 seq="
                    + seq + " eventVersion=" + eventVersion
                    + " expected=" + CURRENT_EVENT_VERSION);
        }
        if (contentType == null) {
            throw new IllegalStateException("[EVENT_CONTENT_MISSING] 完整事件缺少正文 seq=" + seq);
        }
        EventContentType type;
        try {
            type = EventContentType.valueOf(contentType);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[EVENT_SCHEMA_CONTENT_TYPE] 未知正文类型 seq=" + seq);
        }
        EventContent content = new EventContent(type,
                rs.getString("content_json"), rs.getString("content_text"));
        String actualHash = contentHash(content);
        if (!actualHash.equals(contentHash)) {
            throw new IllegalStateException("[EVENT_CONTENT_HASH_MISMATCH] 正文 hash 不一致 seq="
                    + seq);
        }
        return new StoredEvent(seq, occurredAt, eventType, agent, correlationId, turnId,
                eventVersion, visibility, contentHash, false, payloadJson, content);
    }

    /** 对脱敏后的规范正文计算 SHA-256；content type/visibility 是独立元数据，不混入正文 hash。 */
    static String contentHash(EventContent content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content.body().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private static void restrictFilePermissions(Path dbFile) throws IOException {
        try {
            Files.setPosixFilePermissions(dbFile, OWNER_ONLY_PERMISSIONS);
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统无法表达 600；其他访问控制仍由宿主机负责。
        }
    }

    /**
     * SQLite WAL 可能在主库旁生成 -wal/-shm/-journal；这些都是同一事件数据的一部分。
     * 主库权限失败已在打开时 fail-closed；伴随文件只能 best-effort 收口，避免提交成功后
     * 因 chmod 异常反过来让调用方误判事件未落库。
     */
    private static void restrictCompanionFilePermissions(Path dbFile) {
        try {
            Path fileName = dbFile.getFileName();
            if (fileName == null) {
                return;
            }
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                Path companion = dbFile.resolveSibling(fileName + suffix);
                if (!Files.exists(companion)) {
                    continue;
                }
                try {
                    Files.setPosixFilePermissions(companion, OWNER_ONLY_PERMISSIONS);
                } catch (UnsupportedOperationException e) {
                    return;
                } catch (IOException ignored) {
                    // 不覆盖已经提交的事件；主库权限仍由打开路径严格检查。
                }
            }
        } catch (RuntimeException ignored) {
            // companion 权限是提交后的 best-effort：任何 unchecked 异常都不得把已提交
            // 事件报告为失败。主库权限在打开路径仍严格 fail-closed。
        }
    }

    private static void requireUtf8AtMost(String field, String value, int maxBytes) {
        if (value == null) {
            return;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("[EVENT_CONTENT_TOO_LARGE] " + field
                    + " 超过 " + maxBytes + " bytes");
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("[EVENT_DB_CLOSED] 完整事件库已关闭");
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 原始追加异常才是调用方需要看到的根因。
        }
    }

    private static void closeSilently(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 打开失败路径不再用关闭异常覆盖根因。
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException("[EVENT_DB_CLOSE] 关闭完整事件库失败", e);
            }
        }
    }
}
