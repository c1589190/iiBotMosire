package io.mosire.iibot.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.EventAppendRequest;
import io.mosire.iibot.taskflow.event.EventContent;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.StoredEvent;

import java.util.Objects;

/**
 * 基于 {@link EventContentStore} 的完整领域事件 journal。
 *
 * <p>content 序列化进 {@code event_contents.content_json}，summary 序列化进
 * {@code events.payload_json}。header 直接来自 {@link DomainEvent}：{@code agent}
 * 固定为 {@code role:id}，visibility/occurredAt/correlationId/turnId 原样落库，
 * eventVersion 固定为当前内容 schema 版本。序列化使用严格 JSON 配置，失败时不写半条
 * 事件，也不把正文写入异常消息。</p>
 */
public final class SqliteDomainEventJournal implements DomainEventJournal {

    private static final ObjectMapper MAPPER = createMapper();

    private final EventContentStore contentStore;

    public SqliteDomainEventJournal(EventContentStore contentStore) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
    }

    /** 共享底层 store（诊断/迁移对账用；调用方不得借此绕过 journal 写领域事件）。 */
    public EventContentStore contentStore() {
        return contentStore;
    }

    @Override
    public long append(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        String contentJson = serialize(event.content(), "content", event.eventType());
        String summaryJson = serialize(event.summary(), "summary", event.eventType());
        StoredEvent stored = contentStore.append(new EventAppendRequest(
                event.occurredAt(),
                event.eventType(),
                event.actorRole().name() + ":" + event.actorId(),
                event.correlationId(),
                event.turnId(),
                EventContentStore.CURRENT_EVENT_VERSION,
                event.visibility(),
                new EventContent(EventContentType.JSON, contentJson, null),
                summaryJson));
        return stored.seq();
    }

    private static String serialize(Object value, String field, String eventType) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("领域事件 " + field + " 序列化失败 type="
                    + eventType + " error=" + e.getClass().getSimpleName());
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}
