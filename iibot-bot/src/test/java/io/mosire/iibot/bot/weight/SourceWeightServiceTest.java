package io.mosire.iibot.bot.weight;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.InboundMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceWeightServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final ChatSourceKey PRIVATE = ChatSourceKey.privateChat("bot-1", "qq-1");

    @Test
    void firstContactThenAmbientBelowThresholdAndMentionWeighted() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        SourceWeightService service = service(board);
        SourceWeightPolicy initial = service.ensurePolicy(GROUP, false);

        MessageWeightDecision first = decide(service, board, initial,
                inbound("m-1", "普通群聊", false, ""));
        assertThat(first.bootstrap()).isTrue();
        assertThat(first.actionable()).isTrue();
        assertThat(first.wakeReason()).isEqualTo(WeightWakeReason.FIRST_CONTACT);

        service.saveDecision(first);
        service.markBootstrapDelivered(GROUP.threadId());
        SourceWeightPolicy afterBootstrap = service.findPolicy(GROUP.threadId()).orElseThrow();

        MessageWeightDecision ambient = decide(service, board, afterBootstrap,
                inbound("m-2", "普通群聊", false, ""));
        assertThat(ambient.actionable()).isFalse();
        assertThat(ambient.totalScore()).isZero();
        assertThat(ambient.wakeReason()).isEqualTo(WeightWakeReason.BELOW_THRESHOLD);

        MessageWeightDecision mention = decide(service, board, afterBootstrap,
                inbound("m-3", "你看这个", true, ""));
        assertThat(mention.actionable()).isTrue();
        assertThat(mention.totalScore()).isEqualTo(200);
        assertThat(mention.wakeReason()).isEqualTo(WeightWakeReason.MENTION);
        assertThat(mention.matchedSignals()).contains(WeightSignal.MENTION);
    }

    @Test
    void privateIsDirectByDefaultAndKeywordCanCrossThreshold() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        SourceWeightService service = service(board);
        service.ensurePolicy(PRIVATE, false);
        service.markBootstrapDelivered(PRIVATE.threadId());
        SourceWeightPolicy privatePolicy = service.findPolicy(PRIVATE.threadId()).orElseThrow();

        MessageWeightDecision direct = decide(service, board, privatePolicy,
                privateInbound("p-1", "私聊内容"));
        assertThat(direct.actionable()).isTrue();
        assertThat(direct.totalScore()).isEqualTo(500);
        assertThat(direct.wakeReason()).isEqualTo(WeightWakeReason.DM);

        InboundMessage keywordInbound = inbound("g-1", "我上线了", false, "");
        ChatMessage stored = board.append(toChatMessage(keywordInbound)).message();
        SourceWeightPolicy keywordPolicy = service.setPolicy(
                GROUP.threadId(), 0, 100, Map.of(), Map.of("上线", 120), 20,
                180000L, 60000L, null);
        MessageWeightDecision keyword = service.evaluate(
                keywordInbound, stored, keywordPolicy);
        assertThat(keyword.actionable()).isTrue();
        assertThat(keyword.totalScore()).isEqualTo(120);
        assertThat(keyword.wakeReason()).isEqualTo(WeightWakeReason.KEYWORD);
        assertThat(keyword.matchedKeywords()).containsExactly("上线");
    }

    @Test
    void sourceWeightCanRaiseAmbientIntoNotification() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        SourceWeightService service = service(board);
        InboundMessage sourceInbound = inbound("g-1", "普通群聊", false, "");
        ChatMessage stored = board.append(toChatMessage(sourceInbound)).message();
        SourceWeightPolicy policy = service.setPolicy(
                GROUP.threadId(), 500, 100, Map.of(), Map.of(), 20,
                180000L, 60000L, null);

        MessageWeightDecision decision = service.evaluate(sourceInbound, stored, policy);

        assertThat(decision.actionable()).isTrue();
        assertThat(decision.totalScore()).isEqualTo(500);
        assertThat(decision.wakeReason()).isEqualTo(WeightWakeReason.SOURCE_WEIGHT);
    }

    private static SourceWeightService service(InMemoryMessageBoardStore board) {
        return new SourceWeightService(new InMemorySourceWeightStore(), board,
                WeightSettings.defaults(), CLOCK);
    }

    private static MessageWeightDecision decide(SourceWeightService service,
                                                InMemoryMessageBoardStore board,
                                                SourceWeightPolicy policy,
                                                InboundMessage inbound) {
        ChatMessage stored = board.append(toChatMessage(inbound)).message();
        return service.evaluate(inbound, stored, policy);
    }

    private static ChatMessage toChatMessage(InboundMessage inbound) {
        ChatSourceKey source = inbound.sessionType() == InboundMessage.SessionType.PRIVATE
                ? ChatSourceKey.privateChat(inbound.selfId(), inbound.userId())
                : ChatSourceKey.groupChat(inbound.selfId(), inbound.groupId());
        return ChatMessage.inbound(source, inbound.messageId(), inbound.userId(),
                inbound.senderName(), T0, T0, inbound.text(),
                inbound.nonTextSummary(), inbound.replyToMessageId());
    }

    private static InboundMessage inbound(String id, String text, boolean mentioned,
                                          String replyTo) {
        return new InboundMessage("adapter-1", "bot-1", id, "qq-1", "Alice",
                InboundMessage.SessionType.GROUP, "group-9", text, "", replyTo,
                mentioned, T0, "dedupe-" + id);
    }

    private static InboundMessage privateInbound(String id, String text) {
        return new InboundMessage("adapter-1", "bot-1", id, "qq-1", "Alice",
                InboundMessage.SessionType.PRIVATE, null, text, "", "",
                false, T0, "dedupe-" + id);
    }
}
