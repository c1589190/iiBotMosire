package io.mosire.iibot.app.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.inbox.client.InboxModels;
import io.mosire.inbox.embedded.EmbeddedInboxOptions;
import io.mosire.inbox.embedded.EmbeddedInboxServer;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.inbox.InboxDigestItem;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.mcp.BotThreadAckTool;
import io.mosire.iibot.bot.inbox.InboxDigestState;
import io.mosire.iibot.bot.inbox.InMemoryInboxDigestStateStore;
import io.mosire.iibot.bot.note.InMemoryUserNoteStore;
import io.mosire.iibot.bot.note.UserNoteDraft;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
class InboxClientDigestPortTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @TempDir
    Path tempDir;

    @Test
    void inboxContentCarriesRecentContext() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox-context"))
                        .inboxId("app-context")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxDigestService service = new InboxDigestService(
                    board, notes, states,
                    new InboxClientDigestPort(inbox.client(), "app-context"));
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "第一条", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                    "第二条", "", ""));
            InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
            long revision = states.find(GROUP.threadId()).orElseThrow().revision();
            service.markHandled(GROUP.threadId(), first.itemId(), revision);
            board.append(ChatMessage.outbound(GROUP, "reply-1", T0.plusSeconds(1),
                    "我回复", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                    "第三条", "", ""));
            InboxDigestService.FlushResult second = service.flushThread(GROUP.threadId());

            JsonNode content = (JsonNode) inbox.client()
                    .view("app-context", second.itemId()).content();
            assertThat(content.path("context")).hasSize(3);
            assertThat(content.path("context").get(2).path("direction").asText())
                    .isEqualTo("OUT");
            assertThat(content.path("messages")).hasSize(1);
        }
    }

    @Test
    void ackUsesItemContentCursorWhenLocalDigestStateLags() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox-ack-lag"))
                        .inboxId("app-ack-lag")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxDigestService service = new InboxDigestService(
                    board, notes, states,
                    new InboxClientDigestPort(inbox.client(), "app-ack-lag"));
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "第一条", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                    "第二条", "", ""));
            InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
            InboxDigestState active = states.find(GROUP.threadId()).orElseThrow();
            states.save(new InboxDigestState(
                    active.threadId(), active.itemId(), active.revision(), 1L));

            service.markHandled(GROUP.threadId(), first.itemId(), active.revision());

            assertThat(states.find(GROUP.threadId())).get()
                    .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                    .containsExactly("", 2L);
            board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                    "第三条", "", ""));
            InboxDigestService.FlushResult next = service.flushThread(GROUP.threadId());
            assertThat(next.fromSeq()).isEqualTo(3L);
            assertThat(next.messageCount()).isEqualTo(1);
        }
    }

    @Test
    void ackReturnsStableInboxErrorCodes() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox-errors"))
                        .inboxId("app-errors")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxDigestService service = new InboxDigestService(
                    board, notes, states,
                    new InboxClientDigestPort(inbox.client(), "app-errors"));
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "待处理", "", ""));
            InboxDigestService.FlushResult flush = service.flushThread(GROUP.threadId());
            long revision = states.find(GROUP.threadId()).orElseThrow().revision();

            ToolResult stale = ack(service, GROUP.threadId(), flush.itemId(), revision + 1L);
            assertThat(stale.code()).isEqualTo("ITEM_REVISION_CONFLICT");

            inbox.client().claim("app-errors", List.of(flush.itemId()), Duration.ofSeconds(60));
            long claimedRevision = inbox.client()
                    .view("app-errors", flush.itemId()).revision();
            ToolResult claimed = ack(service, GROUP.threadId(), flush.itemId(),
                    claimedRevision);
            assertThat(claimed.code()).isEqualTo("ITEM_STATUS_CONFLICT");
            assertThat(claimed.message())
                    .contains("\"itemStatus\":\"claimed\"")
                    .contains("\"allowedActions\"");
        }
    }

    @Test
    void usesItemContentCursorWhenLocalDigestStateLags() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox-lag"))
                        .inboxId("app-lag")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxClientDigestPort port = new InboxClientDigestPort(inbox.client(), "app-lag");
            InboxDigestService service = new InboxDigestService(board, notes, states, port);
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "第一条", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                    "第二条", "", ""));
            InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
            InboxDigestState active = states.find(GROUP.threadId()).orElseThrow();
            states.save(new InboxDigestState(
                    active.threadId(), active.itemId(), active.revision(), 1L));
            InboxDigestService.FlushResult caughtUp = service.flushThread(GROUP.threadId());
            assertThat(caughtUp.published()).isFalse();
            assertThat(states.find(GROUP.threadId()).orElseThrow().lastSeq()).isEqualTo(2L);

            board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                    "第三条", "", ""));
            InboxDigestService.FlushResult appended = service.flushThread(GROUP.threadId());
            assertThat(appended.published()).isTrue();
            assertThat(appended.itemId()).isEqualTo(first.itemId());
            assertThat(states.find(GROUP.threadId()).orElseThrow().lastSeq()).isEqualTo(3L);

            service.markHandled(GROUP.threadId(), first.itemId(),
                    states.find(GROUP.threadId()).orElseThrow().revision(), 2L);
            InboxDigestState remaining = states.find(GROUP.threadId()).orElseThrow();
            assertThat(remaining.itemId()).isEqualTo(first.itemId());
            JsonNode content = (JsonNode) inbox.client()
                    .view("app-lag", remaining.itemId()).content();
            assertThat(content.path("messages")).hasSize(1);
            assertThat(content.path("messages").get(0).path("summary").asText())
                    .contains("第三条");
        }
    }

    @Test
    void ackThroughConsumesOnlyDeliveredRangeAndArchivesAfterExplicitAck() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox-ack-through"))
                        .inboxId("app-ack-through")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxDigestService service = new InboxDigestService(
                    board, notes, states,
                    new InboxClientDigestPort(inbox.client(), "app-ack-through"));
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "第一条", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                    "第二条", "", ""));
            InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());

            board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                    "第三条", "", ""));
            InboxDigestService.AckResult partial = service.ackThrough(GROUP.threadId(), 1L);

            assertThat(partial.archived()).isFalse();
            InboxDigestState remaining = states.find(GROUP.threadId()).orElseThrow();
            assertThat(remaining.itemId()).isEqualTo(first.itemId());
            JsonNode partialContent = (JsonNode) inbox.client()
                    .view("app-ack-through", remaining.itemId()).content();
            assertThat(partialContent.path("messages"))
                    .extracting(value -> value.path("summary").asText())
                    .anyMatch(value -> value.contains("第二条"))
                    .anyMatch(value -> value.contains("第三条"));
            assertThat(partialContent.path("unreadCount").asInt()).isEqualTo(2);

            InboxDigestService.AckResult complete = service.ackThrough(GROUP.threadId(), 3L);

            assertThat(complete.archived()).isTrue();
            assertThat(states.find(GROUP.threadId()).orElseThrow().hasActiveItem())
                    .isFalse();
            assertThat(inbox.client().list("app-ack-through", Set.of("qq"), 10).items())
                    .isEmpty();
        }
    }

    @Test
    void readsLegacyContentWithoutNewUnreadMetadata() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox-legacy"))
                        .inboxId("app-legacy")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxClientDigestPort port = new InboxClientDigestPort(inbox.client(), "app-legacy");
            InboxDigestService service = new InboxDigestService(board, notes, states, port);
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "第一条", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                    "第二条", "", ""));
            InboxDigestService.FlushResult current = service.flushThread(GROUP.threadId());
            ObjectNode legacy = (ObjectNode) ((JsonNode) inbox.client()
                    .view("app-legacy", current.itemId()).content()).deepCopy();
            legacy.remove(List.of("source", "trigger", "unreadCount", "hasMoreUnread"));
            InboxModels.PushResult pushed = inbox.client().push("app-legacy",
                    InboxModels.PushItem.builder("qq")
                            .kind("digest")
                            .summary("旧版摘要")
                            .content(legacy.toString())
                            .contentRef("message-board://legacy")
                            .dedupeKey("legacy-item")
                            .priority(0)
                            .build());

            InboxDigestItem viewed = port.view(pushed.itemId()).orElseThrow();

            assertThat(viewed.digest()).isNotNull();
            assertThat(viewed.digest().unreadCount()).isEqualTo(2);
            assertThat(viewed.digest().hasMoreUnread()).isFalse();
            assertThat(viewed.digest().source()).isNull();
            assertThat(viewed.digest().trigger()).isNull();
        }
    }

    @Test
    void publishesReadsAndUpdatesSourceDigestInEmbeddedInbox() throws Exception {
        try (EmbeddedInboxServer inbox = EmbeddedInboxServer.start(
                EmbeddedInboxOptions.builder()
                        .dataDir(tempDir.resolve("inbox"))
                        .inboxId("app-a")
                        .deleteDataOnClose(true)
                        .build())) {
            InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
            InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
            InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
            InboxDigestService service = new InboxDigestService(
                    board, notes, states, new InboxClientDigestPort(inbox.client(), "app-a"));
            notes.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice", Set.of("vip"),
                    "重点联系人", 80), null);
            board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice", T0, T0,
                    "第一条", "", ""));
            board.append(ChatMessage.inbound(GROUP, "m-2", "qq-1", "Alice", T0, T0,
                    "第二条", "", ""));

            InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());

            assertThat(first.published()).isTrue();
            InboxModels.ItemList list = inbox.client().list("app-a", Set.of("qq"), 10);
            assertThat(list.items()).hasSize(1);
            assertThat(list.items().getFirst().summary()).contains("第二条", "#vip");
            InboxModels.ItemDetail detail = inbox.client().view("app-a", first.itemId());
            assertThat(detail.summary()).contains("第二条", "#vip");
            assertThat(detail.content()).isInstanceOf(JsonNode.class);
            JsonNode content = (JsonNode) detail.content();
            assertThat(content.path("threadId").asText()).isEqualTo(GROUP.threadId());
            assertThat(content.path("messages")).hasSize(2);
            assertThat(content.path("unreadCount").asInt()).isEqualTo(2);
            assertThat(content.path("source").path("chatType").asText()).isEqualTo("group");
            assertThat(content.path("source").path("targetId").asText()).isEqualTo("group-9");
            assertThat(content.path("trigger").path("messageId").asText()).isEqualTo("m-2");
            long revision = states.find(GROUP.threadId()).orElseThrow().revision();

            board.append(ChatMessage.inbound(GROUP, "m-3", "qq-1", "Alice", T0, T0,
                    "第三条", "", ""));
            InboxDigestService.FlushResult appended = service.flushThread(GROUP.threadId());

            assertThat(appended.published()).isTrue();
            InboxModels.ItemDetail updated = inbox.client().view("app-a", first.itemId());
            assertThat(updated.summary()).contains("第三条");
            assertThat(updated.revision()).isGreaterThan(revision);
            assertThat(((JsonNode) updated.content()).path("messages")).hasSize(3);
            assertThat(((JsonNode) updated.content()).path("unreadCount").asInt()).isEqualTo(3);

            service.markHandled(GROUP.threadId(), first.itemId(),
                    states.find(GROUP.threadId()).orElseThrow().revision(), 2L);
            InboxDigestState remaining = states.find(GROUP.threadId()).orElseThrow();
            InboxModels.ItemDetail partial = inbox.client().view("app-a", remaining.itemId());
            assertThat(partial.summary()).contains("第三条");
            assertThat(((JsonNode) partial.content()).path("messages")).hasSize(1);

            service.markHandled(GROUP.threadId(), remaining.itemId(), remaining.revision());
            assertThat(inbox.client().list("app-a", Set.of("qq"), 10).items()).isEmpty();
        }
    }

    private static ToolResult ack(InboxDigestService service, String threadId,
                                  String itemId, long expectedRevision) {
        BotThreadAckTool tool = new BotThreadAckTool(service);
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT,
                AgentPermissionSet.builder(AccessToken.DEFAULT)
                        .allow(BotThreadAckTool.NAME)
                        .build(),
                Map.of(),
                Map.of("threadId", threadId,
                        "itemId", itemId,
                        "expectedRevision", expectedRevision)));
    }

}
