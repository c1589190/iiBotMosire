package io.mosire.iibot.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.event.EventAppendRequest;
import io.mosire.iibot.taskflow.event.EventContent;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventSink;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.event.TaskflowEvent;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用 events.db 持久化 Taskflow 审计事件，并写入完整事件正文。
 *
 * <p>P1 起底层不再只写一行摘要：{@code events} 保存可检索 headers，{@code event_contents}
 * 保存完整 payload JSON。正文/元数据都经 {@link EventContentStore} 脱敏、限长、计算 hash，
 * 并在同一事务内提交；因此 {@link #append} 成功返回后才算事件落成。</p>
 *
 * <p><b>兼容双写说明</b>：为保持旧 {@code SqliteEventReadPort} 的读取契约，本阶段仍把完整
 * 领域 payload 同时写入 {@code event_contents.content_json} 和 {@code events.payload_json}。
 * {@code payload_json} 在未来应收口为索引/摘要 header，但 P2 之前不改变旧读取契约。</p>
 *
 * <p><b>当前定位仍是审计，不是领域回放。</b>启动时不会从事件库重建 {@code TaskflowEngine}；
 * 引擎仍在内存里从空状态装配。能读事件不等于引擎已恢复。</p>
 */
public final class AgentLibEventSink implements EventSink, AutoCloseable {

    /** 底层事件类型；读取端口按它过滤审计记录。 */
    public static final String STORE_EVENT_TYPE = "taskflow.event";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private final EventContentStore contentStore;
    private final Path dbFile;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 打开数据目录下的 events.db；不注入已知密钥值。 */
    public AgentLibEventSink(Path dbFile) {
        this(dbFile, List.of());
    }

    /**
     * 打开完整事件存储。
     *
     * @param dbFile  事件库文件路径（父目录自动创建）
     * @param secrets 已知密钥值；若事件 payload/正文意外包含它们，会被替换为 {@code [REDACTED]}
     */
    public AgentLibEventSink(Path dbFile, Collection<String> secrets) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        this.contentStore = new EventContentStore(this.dbFile, secrets);
    }

    @Override
    public synchronized long append(TaskflowEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            throw new IllegalStateException("事件存储已关闭，拒绝追加事件");
        }

        String metadataJson = metadataJson(event);
        String contentJson = payloadJson(event);
        StoredEvent stored = contentStore.append(new EventAppendRequest(
                event.occurredAt(),
                STORE_EVENT_TYPE,
                event.actorRole().name() + ":" + event.actorId(),
                event.turnId() == null ? "" : event.turnId(),
                event.turnId(),
                EventContentStore.CURRENT_EVENT_VERSION,
                EventVisibility.GM,
                new EventContent(EventContentType.JSON, contentJson, null),
                metadataJson));
        return stored.seq();
    }

    /** 事件库文件（诊断用；不含密钥）。 */
    public Path dbFile() {
        return dbFile;
    }

    private static String metadataJson(TaskflowEvent event) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("seq", event.seq());
        root.put("domainSeq", event.seq());
        root.put("type", event.type().name());
        root.put("eventType", event.type().name());
        root.put("actorRole", event.actorRole().name());
        root.put("actorId", event.actorId());
        if (event.turnId() == null) {
            root.putNull("turnId");
        } else {
            root.put("turnId", event.turnId());
        }
        root.put("idempotencyKey", event.idempotencyKey());
        root.put("occurredAt", event.occurredAt().toString());
        ObjectNode payloadNode = root.putObject("payload");
        event.payload().forEach(payloadNode::put);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // 不把原 payload 或序列化异常细节写进上层消息。
            throw new IllegalStateException("事件 metadata 序列化失败: " + event.type());
        }
    }

    private static String payloadJson(TaskflowEvent event) {
        try {
            return MAPPER.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件 payload 序列化失败: " + event.type());
        }
    }

    /**
     * 幂等关闭；与 {@link #append} 使用同一实例锁做生命周期屏障，避免 close 与 append
     * 交错后把已开始的追加写到已关闭连接上。底层 SQLite 连接一并关闭。
     */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        contentStore.close();
    }
}
