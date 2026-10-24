package io.mosire.iibot.app.publication;

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
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.post.PostStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 只依赖 events.db 的推文 Store。
 *
 * <p>{@link #load()} 读取最新 {@code POST_} 完整事件并严格反序列化为
 * {@link PostSnapshot}；{@link #save(PostSnapshot, DomainEvent)} 校验事件 content 与
 * 待保存快照完全一致后，只通过 {@link DomainEventJournal#append} 持久化，绝不写
 * {@code posts.json}。内容损坏、缺正文、非法 JSON、未知字段或尾随 token 都 fail-closed。</p>
 */
public final class EventSourcedPostStore implements PostStore {

    private static final ObjectMapper MAPPER = createMapper();
    private static final String SNAPSHOT_ACTOR = "system:post-store";

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public EventSourcedPostStore(EventContentStore contentStore, DomainEventJournal journal) {
        this(contentStore, journal, Clock.systemUTC());
    }

    public EventSourcedPostStore(EventContentStore contentStore, DomainEventJournal journal,
                                 Clock clock) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EventSourcedPostStore(DomainEventJournal journal, EventContentStore contentStore) {
        this(contentStore, journal);
    }

    public EventSourcedPostStore(DomainEventJournal journal, EventContentStore contentStore,
                                 Clock clock) {
        this(contentStore, journal, clock);
    }

    @Override
    public PostSnapshot load() {
        Optional<StoredEvent> latest = contentStore.latestByTypePrefix(
                DomainEventTypes.POST_PREFIX);
        if (latest.isEmpty()) {
            return PostSnapshot.empty();
        }
        StoredEvent event = latest.orElseThrow();
        if (event.contentMissing() || event.content() == null
                || event.content().type() != EventContentType.JSON) {
            throw new PostStoreException(PostStoreException.Code.CORRUPT,
                    "最新推文事件缺少 JSON 完整正文 seq=" + event.seq());
        }
        try {
            PostSnapshot snapshot = MAPPER.readValue(event.content().json(), PostSnapshot.class);
            if (snapshot == null) {
                throw new PostStoreException(PostStoreException.Code.CORRUPT,
                        "推文事件正文反序列化结果为空 seq=" + event.seq());
            }
            return snapshot;
        } catch (JsonProcessingException failure) {
            throw new PostStoreException(PostStoreException.Code.CORRUPT,
                    "推文事件正文 JSON 无法解析 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IllegalArgumentException failure) {
            throw new PostStoreException(PostStoreException.Code.CORRUPT,
                    "推文事件正文领域校验失败 seq=" + event.seq()
                            + " error=" + failure.getClass().getSimpleName(), failure);
        }
    }

    /**
     * 兼容旧 Store 的单参数写入：生成一个通用完整快照事件。
     * 事件类型仍带 {@code POST_} 前缀，因此可被 {@link #load()} 恢复。
     */
    @Override
    public void save(PostSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Instant now = clock.instant();
        DomainEvent event = new DomainEvent(now, DomainEventTypes.POST_SNAPSHOT,
                ActorRole.SUPERVISOR, SNAPSHOT_ACTOR, "", null, EventVisibility.GM,
                snapshot, Map.of(
                "snapshot", true,
                "postCount", snapshot.size(),
                "collectionCount", snapshot.collectionRevisions().size(),
                "idempotencyCount", snapshot.idempotentResults().size(),
                "auditEntryCount", snapshot.auditLog().size()));
        save(snapshot, event);
    }

    @Override
    public void save(PostSnapshot snapshot, DomainEvent event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        if (!snapshot.equals(event.content())) {
            throw new PostStoreException(PostStoreException.Code.SAVE,
                    "事件 content 与待保存推文快照不一致");
        }
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new PostStoreException(PostStoreException.Code.SAVE,
                    "推文事件 journal 未返回有效 seq");
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
