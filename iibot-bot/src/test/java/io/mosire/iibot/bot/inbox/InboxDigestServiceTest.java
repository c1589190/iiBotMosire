package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.note.InMemoryUserNoteStore;
import io.mosire.iibot.bot.note.UserNoteDraft;
import io.mosire.iibot.bot.summary.MessageSummary;
import io.mosire.iibot.bot.summary.MessageSummaryBuilder;
import io.mosire.iibot.bot.weight.InMemorySourceWeightStore;
import io.mosire.iibot.bot.weight.MessageWeightDecision;
import io.mosire.iibot.bot.weight.SourceWeightPolicy;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.bot.weight.WeightSettings;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.bot.store.MessageBoardStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxDigestServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final ChatSourceKey OTHER_GROUP =
            ChatSourceKey.groupChat("bot-1", "group-10");

    @Test
    void coalescesUnreadInboundMessagesAndAdvancesPastOwnOutboundMessage() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);

        notes.upsert(new UserNoteDraft("bot-1", "qq-1", "Alice", Set.of("vip"),
                "重点", 80), null);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));

        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());

        assertThat(first.published()).isTrue();
        assertThat(first.messageCount()).isEqualTo(2);
        assertThat(first.priority()).isEqualTo(80);
        assertThat(port.lastDigest.messages()).hasSize(2);
        assertThat(port.lastDigest.summary()).contains("第二条");
        InboxDigestState active = states.find(GROUP.threadId()).orElseThrow();
        String itemId = active.itemId();

        board.append(ChatMessage.outbound(GROUP, "reply-1", T0.plusSeconds(1),
                "我先回一句", "", ""));
        InboxDigestService.FlushResult outboundOnly = service.flushThread(GROUP.threadId());

        assertThat(outboundOnly.published()).isFalse();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::lastSeq)
                .isEqualTo(3L);

        board.append(inbound("m-3", "qq-1", "Alice", "第三条"));
        InboxDigestService.FlushResult appended = service.flushThread(GROUP.threadId());

        assertThat(appended.published()).isTrue();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly(itemId, 4L);
        assertThat(port.digests.get(itemId).messages())
                .extracting(MessageSummary::localSeq)
                .containsExactly(1L, 2L, 4L);

        InboxDigestService.AckResult ack = service.markHandled(
                GROUP.threadId(), itemId, states.find(GROUP.threadId()).orElseThrow().revision(),
                2L);

        assertThat(ack.archived()).isFalse();
        InboxDigestState remaining = states.find(GROUP.threadId()).orElseThrow();
        assertThat(remaining.itemId()).isEqualTo(itemId);
        assertThat(port.digests.get(itemId).messages())
                .extracting(MessageSummary::localSeq)
                .containsExactly(4L);
    }

    @Test
    void auditTracksAvailableAndDoneDigestWithoutBody() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        InMemoryInboxDigestAuditStore audit = new InMemoryInboxDigestAuditStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(
                board, notes, states, port, new MessageSummaryBuilder(),
                InboxDigestService.DEFAULT_MAX_MESSAGES_PER_FLUSH, audit, CLOCK);
        board.append(inbound("m-1", "qq-1", "Alice", "需要审计"));

        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());

        assertThat(audit.list(GROUP.threadId(), 10)).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo(first.itemId());
            assertThat(item.status()).isEqualTo("available");
            assertThat(item.summary()).contains("需要审计");
            assertThat(item.ackedAt()).isNull();
        });

        service.markHandled(GROUP.threadId(), first.itemId(), port.items.get(first.itemId()).revision());

        assertThat(audit.list(GROUP.threadId(), 10)).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("done");
            assertThat(item.ackedAt()).isEqualTo(T0);
        });
    }

    @Test
    void claimedActiveItemFailsClosedWithoutCreatingAnotherItem() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        port.markClaimed(first.itemId());

        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));

        assertThatThrownBy(() -> service.flushThread(GROUP.threadId()))
                .isInstanceOfSatisfying(InboxDigestConflictException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ITEM_STATUS_CONFLICT");
                    assertThat(failure.details())
                            .containsEntry("itemStatus", "claimed")
                            .containsKey("allowedActions");
                });
        assertThat(port.items).hasSize(1);
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly(first.itemId(), 1L);
    }

    @Test
    void ackRejectsItemFromAnotherThreadWithoutArchivingIt() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "本 thread"));
        board.append(ChatMessage.inbound(OTHER_GROUP, "m-2", "qq-1", "Alice",
                T0, T0, "另一个 thread", "", ""));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        InboxDigestService.FlushResult other = service.flushThread(OTHER_GROUP.threadId());
        long otherRevision = states.find(OTHER_GROUP.threadId()).orElseThrow().revision();

        assertThatThrownBy(() -> service.markHandled(
                GROUP.threadId(), other.itemId(), otherRevision))
                .isInstanceOfSatisfying(InboxDigestConflictException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("THREAD_ITEM_MISMATCH");
                    assertThat(failure.details())
                            .containsEntry("itemId", other.itemId());
                });
        assertThat(port.items.get(first.itemId()).status()).isEqualTo("available");
        assertThat(port.items.get(other.itemId()).status()).isEqualTo("available");
        assertThat(states.find(GROUP.threadId()).orElseThrow().itemId())
                .isEqualTo(first.itemId());
    }

    @Test
    void digestIncludesRecentContextWithoutAdvancingCursor() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        long firstRevision = states.find(GROUP.threadId()).orElseThrow().revision();
        service.markHandled(GROUP.threadId(), first.itemId(), firstRevision);

        board.append(ChatMessage.outbound(GROUP, "reply-1", T0.plusSeconds(1),
                "我回复一句", "", ""));
        board.append(inbound("m-3", "qq-1", "Alice", "第三条"));
        InboxDigestService.FlushResult second = service.flushThread(GROUP.threadId());

        assertThat(second.fromSeq()).isEqualTo(4L);
        assertThat(port.lastDigest.messages()).extracting(MessageSummary::localSeq)
                .containsExactly(4L);
        assertThat(port.lastDigest.context()).extracting(MessageSummary::localSeq)
                .containsExactly(1L, 2L, 3L);
        assertThat(port.lastDigest.context().getLast().direction())
                .isEqualTo(ChatDirection.OUT);
        assertThat(states.find(GROUP.threadId()).orElseThrow().lastSeq())
                .isEqualTo(4L);
    }

    @Test
    void ackThroughDerivesActiveItemAndConsumesOnlyDeliveredRange() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());

        board.append(inbound("m-3", "qq-1", "Alice", "第三条"));
        InboxDigestService.AckResult partial = service.ackThrough(GROUP.threadId(), 1L);

        assertThat(partial.archived()).isFalse();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly(first.itemId(), 3L);
        assertThat(port.digests.get(first.itemId()).messages())
                .extracting(MessageSummary::localSeq)
                .containsExactly(2L, 3L);

        InboxDigestService.AckResult complete = service.ackThrough(GROUP.threadId(), 3L);

        assertThat(complete.archived()).isTrue();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 3L);
    }

    @Test
    void ackThroughDeliveredBeyondActiveItemArchivesAndAdvancesCursor() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));
        service.flushThread(GROUP.threadId());

        // 新消息尚未 flush，consumer 已读取到 m-4；ack 不能留下永远未读的新 item。
        board.append(inbound("m-3", "qq-1", "Alice", "第三条"));
        board.append(inbound("m-4", "qq-1", "Alice", "第四条"));
        InboxDigestService.AckResult result = service.ackThrough(GROUP.threadId(), 4L);

        assertThat(result.archived()).isTrue();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 4L);
        assertThat(service.flushThread(GROUP.threadId()).published()).isFalse();
    }

    @Test
    void snapshotAckLeavesNewerMessagesInTheSameItem() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        long firstRevision = states.find(GROUP.threadId()).orElseThrow().revision();

        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));
        InboxDigestService.FlushResult appended = service.flushThread(GROUP.threadId());
        assertThat(appended.published()).isTrue();
        assertThat(appended.toSeq()).isEqualTo(2L);
        assertThat(appended.itemId()).isEqualTo(first.itemId());

        InboxDigestService.AckResult firstAck = service.markHandled(
                GROUP.threadId(), first.itemId(),
                states.find(GROUP.threadId()).orElseThrow().revision(), 1L);

        assertThat(firstAck.archived()).isFalse();
        InboxDigestState afterFirstAck = states.find(GROUP.threadId()).orElseThrow();
        assertThat(afterFirstAck.itemId()).isEqualTo(first.itemId());
        assertThat(afterFirstAck.lastSeq()).isEqualTo(2L);
        assertThat(port.digests.get(afterFirstAck.itemId()).messages()).hasSize(1);
        assertThat(port.digests.get(afterFirstAck.itemId()).messages().getFirst().summary())
                .contains("第二条");

        InboxDigestService.AckResult secondAck = service.markHandled(
                GROUP.threadId(), afterFirstAck.itemId(),
                afterFirstAck.revision(), 2L);
        assertThat(secondAck.archived()).isTrue();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 2L);
    }

    @Test
    void reconcilesGapBetweenStateAndActiveItemBeforeAppending() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        ChatMessage m1 = inbound("m-1", "qq-1", "Alice", "第一条");
        ChatMessage m2 = inbound("m-2", "qq-1", "Alice", "第二条");
        ChatMessage m3 = inbound("m-3", "qq-1", "Alice", "第三条");
        board.append(m1);
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        board.append(m2);
        ChatMessage storedM3 = board.append(m3).message();

        // 模拟旧版本留下的空洞：state 只到 1，而 active item 内容从 3 开始。
        InboxDigest thirdOnly = InboxDigest.of(List.of(
                new MessageSummaryBuilder().build(storedM3, null)));
        port.update(first.itemId(), port.items.get(first.itemId()).revision(), thirdOnly);
        assertThat(port.items.get(first.itemId()).fromSeq()).isEqualTo(3L);

        InboxDigestService.FlushResult reconciled = service.flushThread(GROUP.threadId());

        assertThat(reconciled.published()).isFalse();
        assertThat(port.digests.get(first.itemId()).messages())
                .extracting(MessageSummary::localSeq)
                .containsExactly(2L, 3L);
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly(first.itemId(), 3L);
    }

    @Test
    void snapshotAckKeepsMessagesAfterSnapshot() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));
        board.append(inbound("m-3", "qq-1", "Alice", "第三条"));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        long revision = states.find(GROUP.threadId()).orElseThrow().revision();

        InboxDigestService.AckResult partial = service.markHandled(
                GROUP.threadId(), first.itemId(), revision, 2L);

        assertThat(partial.archived()).isFalse();
        assertThat(partial.digestedThroughSeq()).isEqualTo(2L);
        assertThat(partial.remainingToSeq()).isEqualTo(3L);
        assertThat(port.digests.get(first.itemId()).messages())
                .extracting(MessageSummary::localSeq)
                .containsExactly(3L);

        InboxDigestService.AckResult full = service.markHandled(
                GROUP.threadId(), first.itemId(),
                states.find(GROUP.threadId()).orElseThrow().revision(), 3L);
        assertThat(full.archived()).isTrue();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 3L);
    }

    @Test
    void ambientGroupMessageStillCreatesBelowThresholdInboxItem() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        SourceWeightService weights = new SourceWeightService(
                new InMemorySourceWeightStore(), board, WeightSettings.defaults(), CLOCK);
        SourceWeightPolicy policy = weights.ensurePolicy(GROUP, true);
        InboxDigestService service = new InboxDigestService(
                board, notes, states, port, new MessageSummaryBuilder(),
                InboxDigestService.DEFAULT_MAX_MESSAGES_PER_FLUSH, null, CLOCK, weights);

        InboundMessage ambientInbound = inboundEvent("m-ambient", "普通群聊", false);
        ChatMessage ambient = board.append(toChatMessage(ambientInbound)).message();
        weights.saveDecision(weights.evaluate(ambientInbound, ambient, policy));
        InboxDigestService.FlushResult ambientResult = service.flushThread(GROUP.threadId());

        assertThat(ambientResult.published()).isTrue();
        assertThat(port.lastDigest.messages()).singleElement()
                .extracting(MessageSummary::localSeq).isEqualTo(1L);
        assertThat(port.lastDigest.priority()).isZero();
        assertThat(port.lastDigest.wakeReason()).isEqualTo("BELOW_THRESHOLD");
        assertThat(port.lastDigest.weight().totalScore()).isZero();
    }

    @Test
    void ambientMessageRemainsInLaterMentionDigestContext() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        SourceWeightService weights = new SourceWeightService(
                new InMemorySourceWeightStore(), board, WeightSettings.defaults(), CLOCK);
        SourceWeightPolicy policy = weights.ensurePolicy(GROUP, true);
        InboxDigestService service = new InboxDigestService(
                board, notes, states, port, new MessageSummaryBuilder(),
                InboxDigestService.DEFAULT_MAX_MESSAGES_PER_FLUSH, null, CLOCK, weights);

        InboundMessage ambientInbound = inboundEvent("m-ambient", "普通群聊", false);
        ChatMessage ambient = board.append(toChatMessage(ambientInbound)).message();
        weights.saveDecision(weights.evaluate(ambientInbound, ambient, policy));
        InboxDigestService.FlushResult ambientResult = service.flushThread(GROUP.threadId());
        long ambientRevision = states.find(GROUP.threadId()).orElseThrow().revision();
        service.markHandled(GROUP.threadId(), ambientResult.itemId(), ambientRevision);

        InboundMessage mentionInbound = inboundEvent("m-mention", "你看这个", true);
        ChatMessage mention = board.append(toChatMessage(mentionInbound)).message();
        weights.saveDecision(weights.evaluate(mentionInbound, mention, policy));
        InboxDigestService.FlushResult mentionResult = service.flushThread(GROUP.threadId());

        assertThat(mentionResult.published()).isTrue();
        assertThat(port.lastDigest.messages()).extracting(MessageSummary::localSeq)
                .containsExactly(2L);
        assertThat(port.lastDigest.context()).extracting(MessageSummary::localSeq)
                .containsExactly(1L);
    }

    @Test
    void ambientAndMentionMessagesInSameWindowShareOneDigest() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        SourceWeightService weights = new SourceWeightService(
                new InMemorySourceWeightStore(), board, WeightSettings.defaults(), CLOCK);
        SourceWeightPolicy policy = weights.ensurePolicy(GROUP, true);
        InboxDigestService service = new InboxDigestService(
                board, notes, states, port, new MessageSummaryBuilder(),
                InboxDigestService.DEFAULT_MAX_MESSAGES_PER_FLUSH, null, CLOCK, weights);

        InboundMessage ambientInbound = inboundEvent("m-ambient", "普通群聊", false);
        ChatMessage ambient = board.append(toChatMessage(ambientInbound)).message();
        weights.saveDecision(weights.evaluate(ambientInbound, ambient, policy));
        InboundMessage mentionInbound = inboundEvent("m-mention", "你看这个", true);
        ChatMessage mention = board.append(toChatMessage(mentionInbound)).message();
        weights.saveDecision(weights.evaluate(mentionInbound, mention, policy));

        InboxDigestService.FlushResult result = service.flushThread(GROUP.threadId());

        assertThat(result.published()).isTrue();
        assertThat(result.messageCount()).isEqualTo(2);
        assertThat(port.lastDigest.messages()).extracting(MessageSummary::localSeq)
                .containsExactly(1L, 2L);
        assertThat(port.lastDigest.priority()).isEqualTo(200);
        assertThat(port.lastDigest.wakeReason()).isEqualTo("MENTION");
        assertThat(port.lastDigest.weight().totalScore()).isEqualTo(200);
    }

    @Test
    void separateSourcesProduceSeparateDigestItems() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        ChatSourceKey privateSource = ChatSourceKey.privateChat("bot-1", "qq-1");

        board.append(inbound("m-1", "qq-1", "Alice", "群消息"));
        board.append(ChatMessage.inbound(privateSource, "m-2", "qq-1", "Alice",
                T0, T0, "私聊", "", ""));

        List<InboxDigestService.FlushResult> results = service.flushAll(10);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(InboxDigestService.FlushResult::published);
        assertThat(results).extracting(InboxDigestService.FlushResult::itemId)
                .doesNotHaveDuplicates();
        assertThat(port.items).hasSize(2);
    }

    @Test
    void markHandledArchivesAndPreservesCursorForNextMessage() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "需要处理"));

        InboxDigestService.FlushResult result = service.flushThread(GROUP.threadId());
        long revision = states.find(GROUP.threadId()).orElseThrow().revision();

        service.markHandled(GROUP.threadId(), result.itemId(), revision);

        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 1L);
        assertThat(port.items.get(result.itemId()).status()).isEqualTo("done");

        board.append(inbound("m-2", "qq-1", "Alice", "下一条"));
        InboxDigestService.FlushResult next = service.flushThread(GROUP.threadId());

        assertThat(next.fromSeq()).isEqualTo(2L);
        assertThat(next.messageCount()).isEqualTo(1);
        assertThat(next.itemId()).isNotEqualTo(result.itemId());
    }

    @Test
    void partialAckConsumesSnapshotAndKeepsSameUnreadBucket() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(
                board, notes, states, port, new MessageSummaryBuilder(), 2);
        for (int seq = 1; seq <= 5; seq++) {
            board.append(inbound("m-" + seq, "qq-1", "Alice", "消息" + seq));
        }
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        long revision = states.find(GROUP.threadId()).orElseThrow().revision();

        InboxDigestService.AckResult partial = service.markHandled(
                GROUP.threadId(), first.itemId(), revision, 2L);

        assertThat(partial.archived()).isFalse();
        assertThat(partial.remainingItemId()).isEqualTo(first.itemId());
        InboxDigest remainder = port.digests.get(first.itemId());
        assertThat(remainder.fromSeq()).isEqualTo(3L);
        assertThat(remainder.toSeq()).isEqualTo(5L);
        assertThat(remainder.unreadCount()).isEqualTo(3);
        assertThat(remainder.hasMoreUnread()).isTrue();
        assertThat(remainder.messages())
                .extracting(MessageSummary::localSeq).containsExactly(4L, 5L);

        InboxDigestService.AckResult full = service.markHandled(
                GROUP.threadId(), first.itemId(),
                states.find(GROUP.threadId()).orElseThrow().revision(), 5L);
        assertThat(full.archived()).isTrue();
        assertThat(states.find(GROUP.threadId())).get()
                .extracting(InboxDigestState::itemId, InboxDigestState::lastSeq)
                .containsExactly("", 5L);

        board.append(inbound("m-6", "qq-1", "Alice", "消息6"));
        InboxDigestService.FlushResult next = service.flushThread(GROUP.threadId());
        assertThat(next.published()).isTrue();
        assertThat(next.itemId()).isNotEqualTo(first.itemId());
        assertThat(port.lastDigest.messages())
                .extracting(MessageSummary::localSeq).containsExactly(6L);
        assertThat(port.lastDigest.unreadCount()).isEqualTo(1);
    }

    @Test
    void ackSnapshotDoesNotSkipMessageNewerThanLocalCursor() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        board.append(inbound("m-2", "qq-1", "Alice", "第二条"));
        InboxDigestState current = states.find(GROUP.threadId()).orElseThrow();
        states.save(current.withLastSeq(2L));

        InboxDigestService.AckResult ack = service.markHandled(
                GROUP.threadId(), first.itemId(), current.revision(), 1L);

        assertThat(ack.archived()).isTrue();
        InboxDigestState next = states.find(GROUP.threadId()).orElseThrow();
        assertThat(next.itemId()).isNotEqualTo(first.itemId());
        assertThat(next.lastSeq()).isEqualTo(2L);
        assertThat(port.lastDigest.messages())
                .extracting(MessageSummary::localSeq).containsExactly(2L);
    }

    @Test
    void inboxItemCarriesSourceTriggerAndUnreadWindow() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(inbound("m-1", "qq-1", "Alice", "第一条"));
        board.append(inbound("m-2", "qq-2", "Bob", "第二条"));

        InboxDigestService.FlushResult result = service.flushThread(GROUP.threadId());

        assertThat(result.published()).isTrue();
        InboxDigest digest = port.lastDigest;
        assertThat(digest.source()).isNotNull();
        assertThat(digest.source().chatType()).isEqualTo("group");
        assertThat(digest.source().targetId()).isEqualTo("group-9");
        assertThat(digest.trigger()).isNotNull();
        assertThat(digest.trigger().messageId()).isEqualTo("m-2");
        assertThat(digest.trigger().localSeq()).isEqualTo(2L);
        assertThat(digest.unreadCount()).isEqualTo(2);
        assertThat(digest.hasMoreUnread()).isFalse();
    }

    @Test
    void activeItemAppendsAndCapsPreviewWithoutLosingUnreadCount() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(
                board, notes, states, port, new MessageSummaryBuilder(), 2);

        for (int seq = 1; seq <= 5; seq++) {
            board.append(inbound("m-" + seq, "qq-1", "Alice", "消息" + seq));
        }

        InboxDigestService.FlushResult first = service.flushThread(GROUP.threadId());
        assertThat(first.published()).isTrue();
        assertThat(first.itemId()).isNotBlank();
        assertThat(port.lastDigest.messages())
                .extracting(MessageSummary::localSeq).containsExactly(4L, 5L);
        assertThat(port.lastDigest.fromSeq()).isEqualTo(1L);
        assertThat(port.lastDigest.toSeq()).isEqualTo(5L);
        assertThat(port.lastDigest.unreadCount()).isEqualTo(5);
        assertThat(port.lastDigest.hasMoreUnread()).isTrue();

        InboxDigestService.FlushResult noop = service.flushThread(GROUP.threadId());
        assertThat(noop.published()).isFalse();
        assertThat(noop.itemId()).isEmpty();

        board.append(inbound("m-6", "qq-1", "Alice", "消息6"));
        board.append(inbound("m-7", "qq-1", "Alice", "消息7"));
        InboxDigestService.FlushResult appended = service.flushThread(GROUP.threadId());
        assertThat(appended.published()).isTrue();
        assertThat(appended.itemId()).isEqualTo(first.itemId());
        assertThat(port.lastDigest.messages())
                .extracting(MessageSummary::localSeq).containsExactly(6L, 7L);
        assertThat(port.lastDigest.fromSeq()).isEqualTo(1L);
        assertThat(port.lastDigest.toSeq()).isEqualTo(7L);
        assertThat(port.lastDigest.unreadCount()).isEqualTo(7);
        assertThat(port.lastDigest.hasMoreUnread()).isTrue();
    }

    private static InboundMessage inboundEvent(String id, String text, boolean mentioned) {
        return new InboundMessage("adapter-1", "bot-1", id, "qq-1", "Alice",
                InboundMessage.SessionType.GROUP, "group-9", text, "", "",
                mentioned, T0, "dedupe-" + id);
    }

    private static ChatMessage toChatMessage(InboundMessage message) {
        return ChatMessage.inbound(GROUP, message.messageId(), message.userId(),
                message.senderName(), T0, T0, message.text(), "",
                message.replyToMessageId());
    }

    private static ChatMessage inbound(String messageId, String senderId, String senderName,
                                       String text) {
        return ChatMessage.inbound(GROUP, messageId, senderId, senderName, T0, T0, text,
                "", "");
    }

    private static final class FakePort implements InboxDigestPort {
        private final Map<String, InboxDigestItem> items = new LinkedHashMap<>();
        private final Map<String, InboxDigest> digests = new LinkedHashMap<>();
        private long nextItem = 1L;
        private InboxDigest lastDigest;

        @Override
        public InboxDigestItem push(String dedupeKey, InboxDigest digest) {
            lastDigest = digest;
            String itemId = "itm-" + nextItem++;
            InboxDigestItem item = new InboxDigestItem(
                    itemId, 1L, "available", digest.toSeq(), digest.fromSeq(),
                    digest.priority(), digest);
            items.put(itemId, item);
            digests.put(itemId, digest);
            return item;
        }

        @Override
        public Optional<InboxDigestItem> view(String itemId) {
            return Optional.ofNullable(items.get(itemId));
        }

        void markClaimed(String itemId) {
            InboxDigestItem current = items.get(itemId);
            items.put(itemId, new InboxDigestItem(
                    itemId, current.revision() + 1L, "claimed", current.toSeq(),
                    current.fromSeq(), current.priority(), current.digest()));
        }

        @Override
        public InboxDigestItem update(String itemId, long expectedRevision,
                                      InboxDigest digest) {
            InboxDigestItem current = items.get(itemId);
            if (current == null || current.revision() != expectedRevision
                    || !current.available()) {
                throw new InboxDigestConflictException("revision conflict");
            }
            lastDigest = digest;
            InboxDigestItem next = new InboxDigestItem(itemId, current.revision() + 1L,
                    "available", digest.toSeq(), digest.fromSeq(),
                    digest.priority(), digest);
            items.put(itemId, next);
            digests.put(itemId, digest);
            return next;
        }

        @Override
        public void archive(String itemId, long expectedRevision) {
            InboxDigestItem current = items.get(itemId);
            if (current == null || current.revision() != expectedRevision) {
                throw new InboxDigestConflictException("archive conflict");
            }
            items.put(itemId, new InboxDigestItem(
                    itemId, current.revision() + 1L, "done", current.toSeq(),
                    current.fromSeq(), current.priority(), current.digest()));
        }
    }
}
