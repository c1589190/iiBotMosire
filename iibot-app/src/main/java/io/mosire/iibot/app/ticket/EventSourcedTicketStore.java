package io.mosire.iibot.app.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.ticket.TicketSnapshot;
import io.mosire.iibot.taskflow.ticket.TicketStore;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 只依赖 events.db 的工单 Store。
 *
 * <p>load 读取最新 {@code TICKET_} 完整事件并严格反序列化 TicketSnapshot；
 * save(snapshot,event) 校验 event.content 与快照完全一致后只通过 journal 追加，
 * 不写第二份 JSON。损坏、未知字段、尾随 token、缺正文均 fail-closed。</p>
 */
public final class EventSourcedTicketStore implements TicketStore {

    private static final ObjectMapper MAPPER = createMapper();
    private static final String SNAPSHOT_ACTOR = "system:ticket-store";

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public EventSourcedTicketStore(EventContentStore contentStore,
                                   DomainEventJournal journal) {
        this(contentStore, journal, Clock.systemUTC());
    }

    public EventSourcedTicketStore(EventContentStore contentStore,
                                   DomainEventJournal journal, Clock clock) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EventSourcedTicketStore(DomainEventJournal journal,
                                   EventContentStore contentStore) {
        this(contentStore, journal);
    }

    public EventSourcedTicketStore(DomainEventJournal journal,
                                   EventContentStore contentStore, Clock clock) {
        this(contentStore, journal, clock);
    }

    @Override
    public TicketSnapshot load() {
        Optional<StoredEvent> latest = contentStore.latestByTypePrefix(
                DomainEventTypes.TICKET_PREFIX);
        if (latest.isEmpty()) {
            return TicketSnapshot.empty();
        }
        StoredEvent event = latest.orElseThrow();
        if (event.contentMissing() || event.content() == null
                || event.content().type() != EventContentType.JSON) {
            throw new TicketStoreException(TicketStoreException.Code.CORRUPT,
                    "最新工单事件缺少 JSON 完整正文 seq=" + event.seq());
        }
        try {
            TicketSnapshot snapshot = MAPPER.readValue(event.content().json(),
                    TicketSnapshot.class);
            if (snapshot == null) {
                throw new TicketStoreException(TicketStoreException.Code.CORRUPT,
                        "工单事件正文反序列化结果为空 seq=" + event.seq());
            }
            return snapshot;
        } catch (JsonProcessingException failure) {
            throw new TicketStoreException(TicketStoreException.Code.CORRUPT,
                    "工单事件正文 JSON 无法解析 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IllegalArgumentException failure) {
            throw new TicketStoreException(TicketStoreException.Code.CORRUPT,
                    "工单事件正文领域校验失败 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        }
    }

    @Override
    public void save(TicketSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Instant now = clock.instant();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ticketCount", snapshot.size());
        summary.put("idempotencyCount", snapshot.idempotentResults().size());
        DomainEvent event = new DomainEvent(now, DomainEventTypes.TICKET_SNAPSHOT,
                ActorRole.SUPERVISOR, SNAPSHOT_ACTOR, "", null,
                EventVisibility.GM, snapshot, summary);
        save(snapshot, event);
    }

    @Override
    public void save(TicketSnapshot snapshot, DomainEvent event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        if (!snapshot.equals(event.content())) {
            throw new TicketStoreException(TicketStoreException.Code.SAVE,
                    "事件 content 与待保存工单快照不一致");
        }
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new TicketStoreException(TicketStoreException.Code.SAVE,
                    "工单事件 journal 未返回有效 seq");
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
