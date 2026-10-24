package io.mosire.iibot.app.event;

import io.mosire.iibot.app.action.EventSourcedActionPoolStore;
import io.mosire.iibot.app.complaint.ComplaintStoreException;
import io.mosire.iibot.app.complaint.EventSourcedComplaintStore;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.publication.EventSourcedPostStore;
import io.mosire.iibot.app.publication.PostStoreException;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolException;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolSnapshot;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.complaint.ComplaintDeliveryState;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintSnapshot;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P2 事件源 Store：只依赖 events.db 重建快照，写失败不推进状态，损坏/缺正文 fail-closed。 */
class EventSourcedDomainStoresTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor PLAYER_2 = new Actor("player-2", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @TempDir
    Path tempDir;

    @Test
    void postDraftApprovePublishRebuildsFromEventsDbWithoutPostsJson() {
        Path db = tempDir.resolve("post.db");
        Path postsJson = tempDir.resolve("posts.json");
        PostSnapshot expected;
        String postId;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            PostService service = new PostService(
                    new EventSourcedPostStore(store, journal, CLOCK), CLOCK);

            String createKey = "idem-post-create-91f";
            String approveKey = "idem-post-approve-4b7";
            String publishKey = "idem-post-publish-2d8";
            PostCommandResult created = service.createDraft(PLAYER, "港口通报",
                    "公共推文正文-只进 events.db", PostVisibility.PUBLIC, 0, createKey);
            assertThat(created.ok()).isTrue();
            postId = created.postId();
            assertThat(service.approve(GM, postId, 1, approveKey).ok()).isTrue();
            assertThat(service.publish(GM, postId, 2, publishKey).ok()).isTrue();
            expected = service.snapshot();
            assertThat(Files.notExists(postsJson)).isTrue();

            List<StoredEvent> events = store.readAfter(0, 100);
            assertThat(events).extracting(StoredEvent::eventType)
                    .containsExactly("POST_DRAFTED", "POST_APPROVED", "POST_PUBLISHED");
            assertThat(events).extracting(StoredEvent::visibility)
                    .containsExactly(EventVisibility.GM, EventVisibility.GM,
                            EventVisibility.PUBLIC);
            assertThat(events).allSatisfy(event -> {
                assertThat(event.contentMissing()).isFalse();
                assertThat(event.content().json())
                        .contains("港口通报", "公共推文正文-只进 events.db");
                assertThat(event.payloadJson())
                        .doesNotContain("港口通报")
                        .doesNotContain("公共推文正文-只进 events.db")
                        .doesNotContain(createKey, approveKey, publishKey)
                        .doesNotContain("\"idempotencyKey\":")
                        .contains(postId, "idempotencyKeyHash");
            });
        }

        // 同一 events.db 重新构造 service/store；不读取 posts.json。
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            PostService rebuilt = new PostService(
                    new EventSourcedPostStore(store, journal, CLOCK), CLOCK);
            assertThat(rebuilt.snapshot()).isEqualTo(expected);
            assertThat(rebuilt.post(postId)).hasValueSatisfying(post -> {
                assertThat(post.status()).isEqualTo(PostStatus.PUBLISHED);
                assertThat(post.title()).isEqualTo("港口通报");
                assertThat(post.body()).isEqualTo("公共推文正文-只进 events.db");
            });
        }
        assertThat(Files.notExists(postsJson)).isTrue();
    }

    @Test
    void complaintSubmitRespondDeliveryRebuildsFromEventsDbWithoutComplaintsJson() {
        Path db = tempDir.resolve("complaint.db");
        Path complaintsJson = tempDir.resolve("complaints.json");
        ComplaintSnapshot expected;
        String complaintId;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ComplaintService service = new ComplaintService(
                    new EventSourcedComplaintStore(store, journal, CLOCK), CLOCK);

            String submitKey = "idem-complaint-submit-82a";
            String respondKey = "idem-complaint-respond-63c";
            var submitted = service.submit(PLAYER, "组合根投诉",
                    "投诉私密正文-只进 events.db", submitKey);
            assertThat(submitted.ok()).isTrue();
            complaintId = submitted.complaintId();
            assertThat(service.respond(GM, complaintId, 1,
                    "GM 回应私密正文-只进 events.db", respondKey).ok()).isTrue();
            assertThat(service.markDelivered(complaintId, ComplaintDeliveryState.SENT).ok())
                    .isTrue();
            expected = service.snapshot();
            assertThat(Files.notExists(complaintsJson)).isTrue();

            List<StoredEvent> events = store.readAfter(0, 100);
            assertThat(events).extracting(StoredEvent::eventType)
                    .containsExactly("COMPLAINT_SUBMITTED", "COMPLAINT_RESPONDED",
                            "COMPLAINT_DELIVERY_CHANGED");
            assertThat(events).extracting(StoredEvent::visibility)
                    .containsOnly(EventVisibility.SECRETARY);
            assertThat(events).allSatisfy(event -> {
                assertThat(event.contentMissing()).isFalse();
                assertThat(event.content().json())
                        .contains(complaintId, "投诉私密正文-只进 events.db");
                assertThat(event.payloadJson())
                        .contains(complaintId)
                        .doesNotContain(submitKey, respondKey)
                        .doesNotContain("\"idempotencyKey\":")
                        .doesNotContain("投诉私密正文-只进 events.db")
                        .doesNotContain("GM 回应私密正文-只进 events.db");
            });
            assertThat(events.get(0).payloadJson()).contains("idempotencyKeyHash");
            assertThat(events.get(1).payloadJson()).contains("idempotencyKeyHash");
            assertThat(events.get(events.size() - 1).content().json())
                    .contains("GM 回应私密正文-只进 events.db");
        }

        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ComplaintService rebuilt = new ComplaintService(
                    new EventSourcedComplaintStore(store, journal, CLOCK), CLOCK);
            assertThat(rebuilt.snapshot()).isEqualTo(expected);
            assertThat(rebuilt.getForGm(complaintId)).hasValueSatisfying(complaint -> {
                assertThat(complaint.responseBody())
                        .isEqualTo("GM 回应私密正文-只进 events.db");
                assertThat(complaint.deliveryState()).isEqualTo(ComplaintDeliveryState.SENT);
            });
        }
        assertThat(Files.notExists(complaintsJson)).isTrue();
    }

    @Test
    void actionPoolAllTransitionsRebuildFromEventsDbAndSummariesHaveNoText() {
        Path db = tempDir.resolve("action-pool.db");
        ActionPoolSnapshot expected;
        String committedActionId;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService service = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, journal, CLOCK), CLOCK);

            String actionText = "去北门找守卫打听消息";
            String withdrawText = "这条行动会被撤回";
            String submitKey = "idem-action-submit-93f";
            String withdrawSubmitKey = "idem-action-second-7c2";
            String withdrawKey = "idem-action-withdraw-5d1";
            // text == source：summary 不得泄漏正文或 source。
            PlayerAction selected = service.submit(PLAYER, actionText, actionText, submitKey);
            PlayerAction withdrawn = service.submit(PLAYER_2, withdrawText,
                    "private-chat", withdrawSubmitKey);
            service.withdraw(PLAYER_2, withdrawn.actionId(), withdrawKey);
            service.markSelected(selected.actionId(), "pkg-1");
            service.releaseSelected(selected.actionId());
            service.markSelected(selected.actionId(), "pkg-2");
            service.markCommitted(selected.actionId());
            committedActionId = selected.actionId();
            expected = service.snapshot();
            assertThat(expected.action(committedActionId).orElseThrow().status())
                    .isEqualTo(ActionStatus.COMMITTED);

            List<StoredEvent> events = store.readAfter(0, 100);
            assertThat(events).extracting(StoredEvent::eventType)
                    .containsExactly("ACTION_SUBMITTED", "ACTION_SUBMITTED",
                            "ACTION_WITHDRAWN", "ACTION_SELECTED", "ACTION_RELEASED",
                            "ACTION_SELECTED", "ACTION_COMMITTED");
            assertThat(events).extracting(StoredEvent::visibility)
                    .containsOnly(EventVisibility.SECRETARY);
            assertThat(events).allSatisfy(event -> {
                assertThat(event.contentMissing()).isFalse();
                assertThat(event.content().json()).contains(actionText);
                assertThat(event.payloadJson())
                        .contains("actionId", "playerId", "status", "actionCount")
                        .doesNotContain(actionText)
                        .doesNotContain(withdrawText)
                        .doesNotContain(submitKey, withdrawSubmitKey, withdrawKey)
                        .doesNotContain("\"source\":")
                        .doesNotContain("\"idempotencyKey\":");
            });
            assertThat(events.get(0).payloadJson()).contains("idempotencyKeyHash");
            assertThat(events.get(2).payloadJson()).contains("idempotencyKeyHash");
        }

        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ActionPoolService rebuilt = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, journal, CLOCK), CLOCK);
            assertThat(rebuilt.snapshot()).isEqualTo(expected);
            assertThat(rebuilt.getForGm(committedActionId)).hasValueSatisfying(action -> {
                assertThat(action.status()).isEqualTo(ActionStatus.COMMITTED);
                assertThat(action.text()).isEqualTo("去北门找守卫打听消息");
            });
        }
    }

    @Test
    void journalFailureDoesNotAdvancePostComplaintOrActionPoolMemoryState() {
        Path db = tempDir.resolve("journal-failure.db");
        DomainEventJournal failingJournal = event -> {
            throw new IllegalStateException("模拟 events.db 不可写");
        };
        try (EventContentStore store = new EventContentStore(db)) {
            PostService posts = new PostService(
                    new EventSourcedPostStore(store, failingJournal, CLOCK), CLOCK);
            PostSnapshot postsBefore = posts.snapshot();
            PostCommandResult postResult = posts.createDraft(PLAYER, "失败标题",
                    "失败正文", PostVisibility.PUBLIC, 0, "fail-post");
            assertThat(postResult.code()).isEqualTo(PostService.STORE_UNAVAILABLE);
            assertThat(posts.snapshot()).isSameAs(postsBefore);

            ComplaintService complaints = new ComplaintService(
                    new EventSourcedComplaintStore(store, failingJournal, CLOCK), CLOCK);
            ComplaintSnapshot complaintsBefore = complaints.snapshot();
            assertThat(complaints.submit(PLAYER, "失败投诉", "失败正文", "fail-complaint").code())
                    .isEqualTo(ComplaintService.STORE_UNAVAILABLE);
            assertThat(complaints.snapshot()).isSameAs(complaintsBefore);

            ActionPoolService actions = new ActionPoolService(
                    new EventSourcedActionPoolStore(store, failingJournal, CLOCK), CLOCK);
            ActionPoolSnapshot actionsBefore = actions.snapshot();
            assertThatThrownBy(() -> actions.submit(PLAYER, "失败行动", "chat", "fail-action"))
                    .isInstanceOfSatisfying(ActionPoolException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(ActionPoolException.STORE_UNAVAILABLE));
            assertThat(actions.snapshot()).isSameAs(actionsBefore);

            assertThat(store.readAfter(0, 10)).isEmpty();
        }
    }

    @Test
    void missingAndCorruptedContentFailClosedOnStoreLoad() throws Exception {
        Path missingDb = tempDir.resolve("missing-content.db");
        long missingSeq;
        try (EventContentStore store = new EventContentStore(missingDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            PostService service = new PostService(
                    new EventSourcedPostStore(store, journal, CLOCK), CLOCK);
            PostCommandResult created = service.createDraft(PLAYER, "标题", "正文",
                    PostVisibility.PUBLIC, 0, "missing-content");
            assertThat(created.ok()).isTrue();
            missingSeq = store.readAfter(0, 10).get(0).seq();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + missingDb);
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM event_contents WHERE seq = ?");
             PreparedStatement mark = connection.prepareStatement(
                     "UPDATE events SET content_missing = 1 WHERE seq = ?")) {
            delete.setLong(1, missingSeq);
            delete.executeUpdate();
            mark.setLong(1, missingSeq);
            mark.executeUpdate();
        }
        try (EventContentStore store = new EventContentStore(missingDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            EventSourcedPostStore postStore = new EventSourcedPostStore(store, journal, CLOCK);
            assertThatThrownBy(postStore::load)
                    .isInstanceOfSatisfying(PostStoreException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PostStoreException.Code.CORRUPT));
        }

        Path corruptDb = tempDir.resolve("corrupt-content.db");
        long corruptSeq;
        try (EventContentStore store = new EventContentStore(corruptDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            ComplaintService service = new ComplaintService(
                    new EventSourcedComplaintStore(store, journal, CLOCK), CLOCK);
            assertThat(service.submit(PLAYER, "标题", "正文", "corrupt-content").ok()).isTrue();
            corruptSeq = store.readAfter(0, 10).get(0).seq();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + corruptDb);
             PreparedStatement tamper = connection.prepareStatement(
                     "UPDATE event_contents SET content_json = '{broken' WHERE seq = ?")) {
            tamper.setLong(1, corruptSeq);
            tamper.executeUpdate();
        }
        try (EventContentStore store = new EventContentStore(corruptDb)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            EventSourcedComplaintStore complaintStore = new EventSourcedComplaintStore(
                    store, journal, CLOCK);
            assertThatThrownBy(complaintStore::load)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_CONTENT_HASH_MISMATCH]");
        }
    }
}
