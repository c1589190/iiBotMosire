package io.mosire.iibot.app.complaint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.ComplaintSnapshot;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 只依赖 events.db 的投诉 Store。
 *
 * <p>{@link #load()} 读取最新 {@code COMPLAINT_} 完整事件并严格反序列化为
 * {@link ComplaintSnapshot}；{@link #save(ComplaintSnapshot, DomainEvent)} 校验事件
 * content 与快照一致后，只通过 journal 追加，绝不写 {@code complaints.json}。
 * 损坏/未知 JSON 字段/hash 不一致均 fail-closed。</p>
 */
public final class EventSourcedComplaintStore implements ComplaintStore {

    private static final ObjectMapper MAPPER = createMapper();
    private static final String SNAPSHOT_ACTOR = "system:complaint-store";

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public EventSourcedComplaintStore(EventContentStore contentStore, DomainEventJournal journal) {
        this(contentStore, journal, Clock.systemUTC());
    }

    public EventSourcedComplaintStore(EventContentStore contentStore,
                                      DomainEventJournal journal, Clock clock) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EventSourcedComplaintStore(DomainEventJournal journal,
                                      EventContentStore contentStore) {
        this(contentStore, journal);
    }

    public EventSourcedComplaintStore(DomainEventJournal journal,
                                      EventContentStore contentStore, Clock clock) {
        this(contentStore, journal, clock);
    }

    @Override
    public ComplaintSnapshot load() {
        Optional<StoredEvent> latest = contentStore.latestByTypePrefix(
                DomainEventTypes.COMPLAINT_PREFIX);
        if (latest.isEmpty()) {
            return ComplaintSnapshot.empty();
        }
        StoredEvent event = latest.orElseThrow();
        if (event.contentMissing() || event.content() == null
                || event.content().type() != EventContentType.JSON) {
            throw new ComplaintStoreException(ComplaintStoreException.Code.CORRUPT,
                    "最新投诉事件缺少 JSON 完整正文 seq=" + event.seq());
        }
        try {
            ComplaintSnapshot snapshot = MAPPER.readValue(event.content().json(),
                    ComplaintSnapshot.class);
            if (snapshot == null) {
                throw new ComplaintStoreException(ComplaintStoreException.Code.CORRUPT,
                        "投诉事件正文反序列化结果为空 seq=" + event.seq());
            }
            return snapshot;
        } catch (JsonProcessingException failure) {
            throw new ComplaintStoreException(ComplaintStoreException.Code.CORRUPT,
                    "投诉事件正文 JSON 无法解析 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IllegalArgumentException failure) {
            throw new ComplaintStoreException(ComplaintStoreException.Code.CORRUPT,
                    "投诉事件正文领域校验失败 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        }
    }

    /** 兼容旧 Store 的单参数写入：生成一个通用完整快照事件。 */
    @Override
    public void save(ComplaintSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Instant now = clock.instant();
        DomainEvent event = new DomainEvent(now, DomainEventTypes.COMPLAINT_SNAPSHOT,
                ActorRole.SUPERVISOR, SNAPSHOT_ACTOR, "", null, EventVisibility.GM,
                snapshot, Map.of(
                "snapshot", true,
                "complaintCount", snapshot.size(),
                "idempotencyCount", snapshot.idempotentResults().size(),
                "auditEntryCount", snapshot.auditLog().size()));
        save(snapshot, event);
    }

    @Override
    public void save(ComplaintSnapshot snapshot, DomainEvent event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        if (!snapshot.equals(event.content())) {
            throw new ComplaintStoreException(ComplaintStoreException.Code.SAVE,
                    "事件 content 与待保存投诉快照不一致");
        }
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new ComplaintStoreException(ComplaintStoreException.Code.SAVE,
                    "投诉事件 journal 未返回有效 seq");
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}
