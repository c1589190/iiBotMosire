package io.mosire.iibot.app.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageCommandEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.StoredEvent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从 events.db 读取 PACKAGE_COMMAND_* 事件并重建 ActionPackageService。
 *
 * <p>读取有界且按 seq 升序；任何缺正文、非 JSON、未知字段、尾随 token 或领域校验失败
 * 都 fail-closed，不返回半恢复服务。</p>
 */
public final class ActionPackageRecoveryService {

    private static final int PAGE_SIZE = 1_000;
    private static final int MAX_EVENTS = 10_000;
    private static final ObjectMapper MAPPER = createMapper();

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public ActionPackageRecoveryService(EventContentStore contentStore,
                                        DomainEventJournal journal, Clock clock) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ActionPackageService recover(ActionPoolService actions) {
        List<StoredEvent> stored = new ArrayList<>();
        long afterSeq = 0L;
        while (true) {
            List<StoredEvent> page = contentStore.readByTypePrefix(
                    DomainEventTypes.PACKAGE_COMMAND_PREFIX, afterSeq, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (StoredEvent event : page) {
                if (stored.size() >= MAX_EVENTS) {
                    throw new IllegalStateException(
                            "[PACKAGE_RECOVERY_INCOMPLETE] 包命令事件超过恢复上限");
                }
                stored.add(event);
            }
            long nextAfter = page.get(page.size() - 1).seq();
            if (nextAfter <= afterSeq) {
                throw new IllegalStateException(
                        "[PACKAGE_RECOVERY_INCOMPLETE] 包命令分页游标未前进");
            }
            afterSeq = nextAfter;
        }
        List<PackageCommandEvent> events = new ArrayList<>(stored.size());
        for (StoredEvent event : stored) {
            if (event.eventVersion() != EventContentStore.CURRENT_EVENT_VERSION) {
                throw new IllegalStateException(
                        "[PACKAGE_RECOVERY_CORRUPT] 包命令事件版本不支持 seq="
                                + event.seq() + " version=" + event.eventVersion());
            }
            if (event.contentMissing() || event.content() == null
                    || event.content().type() != EventContentType.JSON) {
                throw new IllegalStateException(
                        "[PACKAGE_RECOVERY_CORRUPT] 包命令事件缺少 JSON 完整正文 seq="
                                + event.seq());
            }
            try {
                PackageCommandEvent parsed = MAPPER.readValue(event.content().json(),
                        PackageCommandEvent.class);
                if (parsed == null) {
                    throw new IllegalStateException(
                            "[PACKAGE_RECOVERY_CORRUPT] 包命令事件反序列化结果为空 seq="
                                    + event.seq());
                }
                String expectedType = switch (parsed.phase()) {
                    case STARTED -> DomainEventTypes.PACKAGE_COMMAND_STARTED;
                    case APPLIED -> DomainEventTypes.PACKAGE_COMMAND_APPLIED;
                    case FAILED -> DomainEventTypes.PACKAGE_COMMAND_FAILED;
                };
                if (!expectedType.equals(event.eventType())) {
                    throw new IllegalStateException(
                            "[PACKAGE_RECOVERY_CORRUPT] 包命令事件 type 与 phase 不一致 seq="
                                    + event.seq());
                }
                events.add(parsed);
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException(
                        "[PACKAGE_RECOVERY_CORRUPT] 包命令事件 JSON 无法解析 seq="
                                + event.seq() + " error="
                                + failure.getClass().getSimpleName(), failure);
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException(
                        "[PACKAGE_RECOVERY_CORRUPT] 包命令事件领域校验失败 seq="
                                + event.seq() + " error="
                                + failure.getClass().getSimpleName(), failure);
            }
        }
        return ActionPackageService.recover(actions, clock, events, journal);
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
