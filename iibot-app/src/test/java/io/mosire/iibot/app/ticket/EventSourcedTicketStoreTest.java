package io.mosire.iibot.app.ticket;

import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketSnapshot;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P4 工单事件源：事件类型/顺序、完整 content、无正文 summary、重启重建与写失败零推进。 */
class EventSourcedTicketStoreTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_A = new Actor("player-a", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @TempDir
    Path tempDir;

    @Test
    void ticketEventsCarryFullSnapshotAndRebuildFromEventsDbAfterRestart() {
        Path db = tempDir.resolve("tickets.db");
        TicketSnapshot expected;
        String complaintId;
        String noteId;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService service = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);
            assertThat(service.snapshot()).isEqualTo(TicketSnapshot.empty());
            assertThat(store.readAfter(0, 1000)).isEmpty();

            complaintId = service.submit(PLAYER_A, TicketType.COMPLAINT,
                    "事件投诉标题", "事件投诉正文", "event-complaint").ticketId();
            assertThat(service.claim(GM, complaintId, 1, "event-claim").ok()).isTrue();
            assertThat(service.reply(GM, complaintId, 2,
                    "事件回复正文", "event-reply").ok()).isTrue();
            String queryId = service.submit(PLAYER_A, TicketType.SETTING_QUERY,
                    "事件查询标题", "事件查询正文", "event-query").ticketId();
            assertThat(service.batchReply(GM, List.of(
                    new TicketService.BatchReplyItem(queryId, 1, "批量回复正文")),
                    "event-batch").ok()).isTrue();
            assertThat(service.close(GM, complaintId, 3,
                    "事件关闭原因", "event-close").ok()).isTrue();
            TicketCommandResult note = service.createGmNote(GM,
                    "事件 GM 备注", "仅 GM 备注正文", "event-note");
            assertThat(note.ok()).isTrue();
            noteId = note.ticketId();
            expected = service.snapshot();

            List<StoredEvent> events = store.readAfter(0, 1000);
            assertThat(events).extracting(StoredEvent::eventType)
                    .containsExactly(DomainEventTypes.TICKET_CREATED,
                            DomainEventTypes.TICKET_CLAIMED,
                            DomainEventTypes.TICKET_REPLIED,
                            DomainEventTypes.TICKET_CREATED,
                            DomainEventTypes.TICKET_BATCH_REPLIED,
                            DomainEventTypes.TICKET_CLOSED,
                            DomainEventTypes.TICKET_CREATED);
            assertThat(events).extracting(StoredEvent::visibility)
                    .containsOnly(EventVisibility.GM);
            for (StoredEvent event : events) {
                assertThat(event.contentMissing()).isFalse();
                assertThat(event.content().json())
                        .contains("\"tickets\"", "\"idempotentResults\"");
                assertThat(event.payloadJson())
                        .contains("ticketId", "idempotencyKeyHash")
                        .doesNotContain("事件投诉正文")
                        .doesNotContain("事件回复正文")
                        .doesNotContain("事件查询正文")
                        .doesNotContain("批量回复正文")
                        .doesNotContain("仅 GM 备注正文")
                        .doesNotContain("事件投诉标题");
            }
            StoredEvent replied = events.get(2);
            assertThat(replied.content().json()).contains("事件回复正文");
            StoredEvent batch = events.get(4);
            assertThat(batch.content().json()).contains("批量回复正文");
            StoredEvent closed = events.get(5);
            assertThat(closed.content().json()).contains("事件关闭原因");
        }

        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService rebuilt = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);
            assertThat(rebuilt.snapshot()).isEqualTo(expected);
            Ticket complaint = rebuilt.getForGm(complaintId).orElseThrow();
            assertThat(complaint.status()).isEqualTo(TicketStatus.CLOSED);
            assertThat(complaint.replies()).singleElement()
                    .satisfies(reply -> assertThat(reply.body()).isEqualTo("事件回复正文"));
            assertThat(rebuilt.getForGm(noteId).orElseThrow().type())
                    .isEqualTo(TicketType.GM_NOTE);
        }
    }

    @Test
    void idempotencyFingerprintSurvivesEventDbRestart() {
        Path db = tempDir.resolve("tickets-idempotency-restart.db");
        TicketCommandResult original;
        TicketSnapshot afterSubmit;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService service = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);
            original = service.submit(PLAYER_A, TicketType.COMPLAINT,
                    "重启幂等标题", "重启幂等正文", "restart-idempotency-key");
            assertThat(original.ok()).isTrue();
            afterSubmit = service.snapshot();
        }

        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService rebuilt = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);
            // 相同请求：成功重放，不新增 ticket/事件。
            assertThat(rebuilt.submit(PLAYER_A, TicketType.COMPLAINT,
                    "重启幂等标题", "重启幂等正文", "restart-idempotency-key"))
                    .isEqualTo(original);
            assertThat(rebuilt.snapshot()).isEqualTo(afterSubmit);
            // 不同正文 / 不同命令：冲突且零变更。
            TicketCommandResult bodyConflict = rebuilt.submit(PLAYER_A,
                    TicketType.COMPLAINT, "重启幂等标题", "不同正文",
                    "restart-idempotency-key");
            assertThat(bodyConflict.code())
                    .isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
            assertThat(rebuilt.snapshot()).isEqualTo(afterSubmit);
            TicketCommandResult commandConflict = rebuilt.claim(GM,
                    original.ticketId(), 1, "restart-idempotency-key");
            assertThat(commandConflict.code())
                    .isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
            assertThat(rebuilt.snapshot()).isEqualTo(afterSubmit);
        }
    }

    @Test
    void batchReplySupports20And100TicketsThroughEventSourceAndRejects101() {
        Path db = tempDir.resolve("tickets-large-batch.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService service = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);

            List<String> ids20 = createTickets(service, 20, "batch20");
            assertThat(service.batchReply(GM, batchItems(ids20, "批量20"), "batch-20").ok())
                    .isTrue();
            assertThat(ids20).allSatisfy(id -> assertThat(
                    service.getForGm(id).orElseThrow().status())
                    .isEqualTo(TicketStatus.RESOLVED));

            List<String> ids100 = createTickets(service, 100, "batch100");
            assertThat(service.batchReply(GM, batchItems(ids100, "批量100"), "batch-100").ok())
                    .isTrue();
            assertThat(ids100).allSatisfy(id -> assertThat(
                    service.getForGm(id).orElseThrow().status())
                    .isEqualTo(TicketStatus.RESOLVED));

            List<String> ids101 = createTickets(service, 101, "batch101");
            TicketCommandResult tooLarge = service.batchReply(GM,
                    batchItems(ids101, "批量101"), "batch-101");
            assertThat(tooLarge.ok()).isFalse();
            assertThat(tooLarge.code()).isEqualTo(TicketService.INVALID_ARGUMENT);
            assertThat(ids101).allSatisfy(id -> {
                Ticket ticket = service.getForGm(id).orElseThrow();
                assertThat(ticket.status()).isEqualTo(TicketStatus.OPEN);
                assertThat(ticket.revision()).isEqualTo(1);
                assertThat(ticket.replies()).isEmpty();
            });
            assertThat(store.readAfter(0, 10_000)).filteredOn(event ->
                            event.eventType().equals(DomainEventTypes.TICKET_BATCH_REPLIED))
                    .hasSize(2);
        }
    }

    @Test
    void tamperedTicketEventHashOrVersionFailsClosed() throws Exception {
        Path hashDb = tempDir.resolve("tickets-hash-tampered.db");
        long hashSeq;
        try (EventContentStore store = new EventContentStore(hashDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService service = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);
            service.submit(PLAYER_A, TicketType.COMPLAINT,
                    "hash 篡改标题", "hash 篡改正文", "hash-tamper");
            hashSeq = store.readAfter(0, 10).get(0).seq();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + hashDb);
             PreparedStatement tamper = connection.prepareStatement(
                     "UPDATE events SET content_hash = 'tampered' WHERE seq = ?")) {
            tamper.setLong(1, hashSeq);
            tamper.executeUpdate();
        }
        try (EventContentStore store = new EventContentStore(hashDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            EventSourcedTicketStore ticketStore = new EventSourcedTicketStore(
                    store, journal, CLOCK);
            assertThatThrownBy(ticketStore::load)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_CONTENT_HASH_MISMATCH]");
        }

        Path versionDb = tempDir.resolve("tickets-version-tampered.db");
        long versionSeq;
        try (EventContentStore store = new EventContentStore(versionDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            TicketService service = new TicketService(
                    new EventSourcedTicketStore(store, journal, CLOCK), CLOCK);
            service.submit(PLAYER_A, TicketType.COMPLAINT,
                    "version 篡改标题", "version 篡改正文", "version-tamper");
            versionSeq = store.readAfter(0, 10).get(0).seq();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + versionDb);
             PreparedStatement tamper = connection.prepareStatement(
                     "UPDATE events SET event_version = 999 WHERE seq = ?")) {
            tamper.setLong(1, versionSeq);
            tamper.executeUpdate();
        }
        try (EventContentStore store = new EventContentStore(versionDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            EventSourcedTicketStore ticketStore = new EventSourcedTicketStore(
                    store, journal, CLOCK);
            assertThatThrownBy(ticketStore::load)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_VERSION_UNSUPPORTED]");
        }
    }

    private static List<String> createTickets(TicketService service, int count, String keyPrefix) {
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            TicketCommandResult result = service.submit(PLAYER_A, TicketType.COMPLAINT,
                    "批量标题-" + keyPrefix + "-" + index,
                    "批量正文-" + keyPrefix + "-" + index,
                    keyPrefix + "-submit-" + index);
            assertThat(result.ok()).isTrue();
            ids.add(result.ticketId());
        }
        return ids;
    }

    private static List<TicketService.BatchReplyItem> batchItems(List<String> ids,
                                                                 String bodyPrefix) {
        List<TicketService.BatchReplyItem> items = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            items.add(new TicketService.BatchReplyItem(ids.get(index), 1,
                    bodyPrefix + "-" + index));
        }
        return items;
    }

    @Test
    void journalFailureDoesNotAdvanceStateOrWriteTicketEvents() {
        Path db = tempDir.resolve("tickets-failure.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal failing = event -> {
                throw new IllegalStateException("模拟工单事件写失败");
            };
            TicketService service = new TicketService(
                    new EventSourcedTicketStore(store, failing, CLOCK), CLOCK);
            TicketSnapshot before = service.snapshot();
            TicketCommandResult result = service.submit(PLAYER_A, TicketType.COMPLAINT,
                    "失败标题", "失败正文", "ticket-failure");
            assertThat(result.ok()).isFalse();
            assertThat(result.code()).isEqualTo(TicketService.TICKET_STORE_UNAVAILABLE);
            assertThat(service.snapshot()).isEqualTo(before);
            assertThat(store.readAfter(0, 1000)).isEmpty();
        }
    }
}
