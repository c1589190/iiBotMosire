package io.mosire.iibot.app.inbox;

import io.mosire.inbox.client.InboxModels;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.ticket.InMemoryTicketStore;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InboxNotificationBridgeTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void retriesFailedPushWithSameDedupeKeyWithoutRollingBackDomainState() {
        TicketService tickets = new TicketService(new InMemoryTicketStore(), CLOCK);
        PostService posts = new PostService(new InMemoryPostStore(), CLOCK);
        TicketCommandResult ticket = tickets.submit(
                new Actor("player-1", ActorRole.PLAYER), TicketType.ACTION_DRAFT,
                "SECRET_TICKET_TITLE", "SECRET_TICKET_BODY", "bridge-ticket-1");
        assertThat(ticket.ok()).isTrue();
        PostCommandResult post = posts.createDraft(
                new Actor("player-1", ActorRole.PLAYER), "SECRET_POST_TITLE",
                "SECRET_POST_BODY", PostVisibility.PUBLIC, 0L, "bridge-post-1");
        assertThat(post.ok()).isTrue();

        List<InboxModels.PushItem> attempted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger itemSeq = new AtomicInteger();
        InboxNotificationBridge bridge = new InboxNotificationBridge(
                tickets, posts,
                (inboxId, item) -> {
                    attempted.add(item);
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("simulated push failure");
                    }
                    int seq = itemSeq.incrementAndGet();
                    return new InboxModels.PushResult("itm_" + seq, false, seq,
                            "2026-10-25T00:00:00Z");
                },
                "app-a", Set.of("ticket", "publication-review"), Duration.ofMillis(100));

        try {
            assertThat(bridge.scanOnce()).isEqualTo(1);
            assertThat(tickets.getForGm(ticket.ticketId()).orElseThrow().status())
                    .isEqualTo(TicketStatus.OPEN);
            assertThat(posts.post(post.postId()).orElseThrow().body())
                    .isEqualTo("SECRET_POST_BODY");

            assertThat(bridge.scanOnce()).isEqualTo(1);
            assertThat(bridge.scanOnce()).isZero();

            assertThat(attempted).hasSize(3);
            String ticketKey = "ticket:" + ticket.ticketId() + ":r1";
            String postKey = "post-review:" + post.postId() + ":r1";
            assertThat(attempted).extracting(InboxModels.PushItem::dedupeKey)
                    .containsExactly(ticketKey, postKey, ticketKey);

            InboxModels.PushItem ticketItem = attempted.get(2);
            assertThat(ticketItem.channel()).isEqualTo("ticket");
            assertThat(ticketItem.summary()).isEqualTo("ACTION_DRAFT");
            assertThat(ticketItem.summary()).doesNotContain("SECRET_TICKET");
            assertThat(ticketItem.content()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> ticketContent = (Map<String, Object>) ticketItem.content();
            assertThat(ticketContent).containsOnlyKeys(
                    "ticketId", "type", "status", "revision");
            assertThat(ticketContent.toString()).doesNotContain(
                    "SECRET_TICKET_TITLE", "SECRET_TICKET_BODY", "player-1");

            InboxModels.PushItem postItem = attempted.get(1);
            assertThat(postItem.channel()).isEqualTo("publication-review");
            assertThat(postItem.summary()).isEqualTo("PUBLIC");
            assertThat(postItem.summary()).doesNotContain("SECRET_POST");
            assertThat(postItem.content()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> postContent = (Map<String, Object>) postItem.content();
            assertThat(postContent).containsOnlyKeys("postId", "visibility", "revision");
            assertThat(postContent.toString()).doesNotContain("SECRET_POST_TITLE",
                    "SECRET_POST_BODY", "player-1");
        } finally {
            bridge.close();
        }
    }

    @Test
    void sameRevisionIsConfirmedOnlyOnceEvenWhenServerReportsDuplicate() {
        TicketService tickets = new TicketService(new InMemoryTicketStore(), CLOCK);
        PostService posts = new PostService(new InMemoryPostStore(), CLOCK);
        assertThat(tickets.submit(new Actor("player-1", ActorRole.PLAYER),
                TicketType.COMPLAINT, "title", "body", "bridge-dedupe-1").ok()).isTrue();

        AtomicInteger calls = new AtomicInteger();
        List<String> keys = new ArrayList<>();
        InboxNotificationBridge bridge = new InboxNotificationBridge(
                tickets, posts,
                (inboxId, item) -> {
                    calls.incrementAndGet();
                    keys.add(item.dedupeKey());
                    return new InboxModels.PushResult("itm_duplicate", true, 1,
                            "2026-10-25T00:00:00Z");
                },
                "app-a", Set.of("ticket"), Duration.ofMillis(100));
        try {
            assertThat(bridge.scanOnce()).isZero();
            assertThat(bridge.scanOnce()).isZero();
            assertThat(calls).hasValue(1);
            assertThat(keys).containsExactly("ticket:" + tickets.listForGm().getFirst().ticketId()
                    + ":r1");
        } finally {
            bridge.close();
        }
    }
}
