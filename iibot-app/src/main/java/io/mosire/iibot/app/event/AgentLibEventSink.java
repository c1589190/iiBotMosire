package io.mosire.iibot.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.iibot.taskflow.event.EventSink;
import io.mosire.iibot.taskflow.event.TaskflowEvent;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用 AgentLib {@link SqliteEventStore} 持久化 Taskflow 事件审计。
 *
 * <p><b>当前定位：审计，不是领域回放。</b>启动时不会从事件库重建 {@code TaskflowEngine}；
 * 引擎仍在内存里从空状态装配，事件库只保留跨重启可读的追加审计记录。若未来要恢复领域状态，
 * 必须另建投影/重放器并通过不变量校验，不能把“能读到事件”冒充“已恢复引擎”。</p>
 *
 * <p>每条底层记录：{@code type=taskflow.event}，payload 是完整、脱敏的 JSON（含领域 seq、
 * 角色、actorId、turnId、幂等键、发生时间与原始 payload 字符串映射）。{@link #append} 返回
 * SQLite 分配的底层 seq。</p>
 */
public final class AgentLibEventSink implements EventSink, AutoCloseable {

    /** 底层事件类型；读取端口按它过滤审计记录。 */
    public static final String STORE_EVENT_TYPE = "taskflow.event";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private final SqliteEventStore store;
    private final EventRedactor redactor;
    private final Path dbFile;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 打开数据目录下的 events.db；不注入已知密钥值。 */
    public AgentLibEventSink(Path dbFile) {
        this(dbFile, List.of());
    }

    /**
     * 打开事件存储。
     *
     * @param dbFile  事件库文件路径（父目录自动创建）
     * @param secrets 已知密钥值；若未来 payload 意外包含它们，会被替换为 {@code [REDACTED]}
     */
    public AgentLibEventSink(Path dbFile, Collection<String> secrets) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        this.redactor = new EventRedactor(secrets);
        this.store = SqliteEventStore.open(this.dbFile);
    }

    @Override
    public synchronized long append(TaskflowEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            throw new IllegalStateException("事件存储已关闭，拒绝追加事件");
        }
        Map<String, String> safePayload = redactor.sanitize(event.payload());
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("seq", event.seq());
        root.put("domainSeq", event.seq());
        root.put("type", event.type().name());
        root.put("eventType", event.type().name());
        root.put("actorRole", event.actorRole().name());
        root.put("actorId", redactor.sanitize("actorId", event.actorId()));
        if (event.turnId() == null) {
            root.putNull("turnId");
        } else {
            root.put("turnId", redactor.sanitize("turnId", event.turnId()));
        }
        root.put("idempotencyKey", redactor.sanitize("idempotencyKey", event.idempotencyKey()));
        root.put("occurredAt", event.occurredAt().toString());
        ObjectNode payloadNode = root.putObject("payload");
        safePayload.forEach(payloadNode::put);

        String json;
        try {
            json = MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // 不把原 payload 或序列化异常细节写进上层消息。
            throw new IllegalStateException("事件 payload 序列化失败: " + event.type());
        }
        Event stored = store.append(new EventWrite(
                STORE_EVENT_TYPE,
                event.actorRole().name() + ":" + event.actorId(),
                json,
                event.turnId() == null ? "" : event.turnId()));
        return stored.seq();
    }

    /** 事件库文件（诊断用；不含密钥）。 */
    public Path dbFile() {
        return dbFile;
    }

    /** 幂等关闭；底层 SQLite 连接一并关闭。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        store.close();
    }
}
