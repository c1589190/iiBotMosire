package io.mosire.iibot.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.control.EventReadPort;
import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.event.TaskflowEvent;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 {@link AgentLibEventSink} 同一个 SQLite 库上建立只读事件读取端口。
 *
 * <p>AgentLib 的 {@code EventStore} 没有 {@code seq > afterSeq} 的升序 API，因此这里用
 * 只读 JDBC 直接查询 {@code events WHERE seq > ? AND type = 'taskflow.event' ORDER BY seq ASC
 * LIMIT ?}。连接优先 {@code mode=ro}，失败时退化为普通连接 + {@code PRAGMA query_only=1}；
 * 两种方式都不会写数据。{@code afterSeq} 是排他游标，{@code limit} 严格生效。</p>
 *
 * <p><b>重要</b>：本读取端口只做审计读取，不消费游标，也不触发领域回放。重启后能读到事件
 * 不等于引擎已恢复。</p>
 */
public final class SqliteEventReadPort implements EventReadPort, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SELECT_AFTER =
            "SELECT seq, payload FROM events WHERE seq > ? AND type = ? ORDER BY seq ASC LIMIT ?";
    private static final String SELECT_LAST_STORED_SEQ =
            "SELECT COALESCE(MAX(seq), 0) AS last_seq FROM events WHERE type = ?";

    private final Path dbFile;
    private final Connection connection;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteEventReadPort(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.dbFile)) {
            throw new IllegalStateException("只读事件库不存在: " + this.dbFile);
        }
        this.connection = openReadOnly(this.dbFile);
    }

    @Override
    public synchronized List<TaskflowEvent> readAfter(long afterSeq, int limit) {
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正");
        }
        if (closed.get()) {
            throw new IllegalStateException("事件读取端口已关闭");
        }
        try (PreparedStatement statement = connection.prepareStatement(SELECT_AFTER)) {
            statement.setLong(1, afterSeq);
            statement.setString(2, AgentLibEventSink.STORE_EVENT_TYPE);
            statement.setInt(3, limit);
            List<TaskflowEvent> events = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(parse(rs.getLong("seq"), rs.getString("payload")));
                }
            }
            return List.copyOf(events);
        } catch (SQLException e) {
            throw new IllegalStateException("读取审计事件失败", e);
        }
    }

    /**
     * 返回兼容 {@code taskflow.event} 审计流当前最大 seq；空库为 0。
     *
     * <p>P2 起 events.db 同时存放 POST_/COMPLAINT_ 等领域事件，Taskflow 快照恢复锚点只允许
     * 看旧 {@code taskflow.event} 类型，不能被其他领域事件推进。该查询只读，不消费游标。</p>
     */
    public synchronized long lastStoredSeq() {
        if (closed.get()) {
            throw new IllegalStateException("事件读取端口已关闭");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_LAST_STORED_SEQ)) {
            statement.setString(1, AgentLibEventSink.STORE_EVENT_TYPE);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong("last_seq");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取事件最大 seq 失败", e);
        }
    }

    private static TaskflowEvent parse(long seq, String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计事件 payload 不是合法 JSON: seq=" + seq);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("审计事件 payload 不是 JSON 对象: seq=" + seq);
        }
        try {
            long domainSeq = root.path("domainSeq").asLong(0L);
            EventType type = EventType.valueOf(requiredText(root, "eventType", seq));
            ActorRole role = ActorRole.valueOf(requiredText(root, "actorRole", seq));
            String actorId = requiredText(root, "actorId", seq);
            String turnId = root.hasNonNull("turnId") ? root.get("turnId").asText() : null;
            String idempotencyKey = requiredText(root, "idempotencyKey", seq);
            Instant occurredAt = Instant.parse(requiredText(root, "occurredAt", seq));
            Map<String, String> payload = new LinkedHashMap<>();
            JsonNode payloadNode = root.get("payload");
            if (payloadNode != null && !payloadNode.isNull()) {
                if (!payloadNode.isObject()) {
                    throw new IllegalStateException("审计事件 payload 字段不是对象: seq=" + seq);
                }
                payloadNode.properties().forEach(entry ->
                        payload.put(entry.getKey(), entry.getValue().asText()));
            }
            // TaskflowEvent 的 seq 由存储分配；domainSeq 在 payload 中保留原始领域序号。
            return new TaskflowEvent(seq, occurredAt, type, role, actorId, turnId,
                    idempotencyKey, payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("审计事件字段无法解析: seq=" + seq);
        }
    }

    private static String requiredText(JsonNode root, String field, long seq) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("审计事件缺少字段 " + field + ": seq=" + seq);
        }
        return node.asText();
    }

    private static Connection openReadOnly(Path dbFile) {
        try {
            return DriverManager.getConnection(
                    "jdbc:sqlite:file:" + dbFile.toAbsolutePath() + "?mode=ro");
        } catch (SQLException uriFailure) {
            try {
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only = 1");
                }
                return connection;
            } catch (SQLException fallbackFailure) {
                fallbackFailure.addSuppressed(uriFailure);
                throw new IllegalStateException("只读打开事件库失败: " + dbFile, fallbackFailure);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("关闭事件读取端口失败", e);
        }
    }
}
