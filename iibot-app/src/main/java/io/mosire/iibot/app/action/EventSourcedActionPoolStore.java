package io.mosire.iibot.app.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolException;
import io.mosire.iibot.taskflow.action.ActionPoolSnapshot;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
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
 * 只依赖 events.db 的行动池 Store。
 *
 * <p>{@link #load()} 读取最新 {@code ACTION_} 完整事件并严格反序列化为
 * {@link ActionPoolSnapshot}；{@link #save(ActionPoolSnapshot, DomainEvent)} 校验
 * content 与快照完全一致后只通过 journal 追加，不另写 JSON/内存第二事实源。
 * 缺正文、hash 不一致、非法 JSON、未知字段或尾随 token 均 fail-closed。</p>
 */
public final class EventSourcedActionPoolStore implements ActionPoolStore {

    private static final ObjectMapper MAPPER = createMapper();
    private static final String SNAPSHOT_ACTOR = "system:action-pool-store";

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public EventSourcedActionPoolStore(EventContentStore contentStore,
                                       DomainEventJournal journal) {
        this(contentStore, journal, Clock.systemUTC());
    }

    public EventSourcedActionPoolStore(EventContentStore contentStore,
                                       DomainEventJournal journal, Clock clock) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EventSourcedActionPoolStore(DomainEventJournal journal,
                                       EventContentStore contentStore) {
        this(contentStore, journal);
    }

    public EventSourcedActionPoolStore(DomainEventJournal journal,
                                       EventContentStore contentStore, Clock clock) {
        this(contentStore, journal, clock);
    }

    @Override
    public ActionPoolSnapshot load() {
        Optional<StoredEvent> latest = contentStore.latestByTypePrefix(
                DomainEventTypes.ACTION_PREFIX);
        if (latest.isEmpty()) {
            return ActionPoolSnapshot.empty();
        }
        StoredEvent event = latest.orElseThrow();
        if (event.contentMissing() || event.content() == null
                || event.content().type() != EventContentType.JSON) {
            throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                    "最新行动池事件缺少 JSON 完整正文 seq=" + event.seq());
        }
        try {
            ActionPoolSnapshot snapshot = MAPPER.readValue(event.content().json(),
                    ActionPoolSnapshot.class);
            if (snapshot == null) {
                throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                        "行动池事件正文反序列化结果为空 seq=" + event.seq());
            }
            return snapshot;
        } catch (JsonProcessingException failure) {
            throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                    "行动池事件正文 JSON 无法解析 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IllegalArgumentException failure) {
            throw new ActionPoolException(ActionPoolException.CORRUPT_SNAPSHOT,
                    "行动池事件正文领域校验失败 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        }
    }

    /** 兼容旧 Store 的单参数写入：生成一个通用完整快照事件。 */
    @Override
    public void save(ActionPoolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Instant now = clock.instant();
        DomainEvent event = new DomainEvent(now, DomainEventTypes.ACTION_SNAPSHOT,
                ActorRole.SUPERVISOR, SNAPSHOT_ACTOR, "", null, EventVisibility.GM,
                snapshot, Map.of(
                "snapshot", true,
                "actionCount", snapshot.actions().size(),
                "idempotentPlayerCount", snapshot.idempotentResults().size()));
        save(snapshot, event);
    }

    @Override
    public void save(ActionPoolSnapshot snapshot, DomainEvent event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        if (!snapshot.equals(event.content())) {
            throw new ActionPoolException(ActionPoolException.STORE_UNAVAILABLE,
                    "事件 content 与待保存行动池快照不一致");
        }
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new ActionPoolException(ActionPoolException.STORE_UNAVAILABLE,
                    "行动池事件 journal 未返回有效 seq");
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
