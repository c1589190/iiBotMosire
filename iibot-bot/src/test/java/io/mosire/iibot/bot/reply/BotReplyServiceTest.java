package io.mosire.iibot.bot.reply;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.OutboundSegment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotReplyServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @Test
    void persistsAndSendsOriginalTextWithoutProgramNormalization() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "原消息", "", ""));
        CapturingPort port = new CapturingPort();
        BotReplyService service = new BotReplyService(board, port, CLOCK);

        BotReplyResult result = service.reply(GROUP.threadId(),
                "这是包含 MCP 的原文", null, "reply-normalized");

        assertThat(result.message().text()).isEqualTo("这是包含 MCP 的原文");
        assertThat(port.sentText).isEqualTo("这是包含 MCP 的原文");
        ChatMessage stored = board.find(result.message().messageKey()).orElseThrow();
        assertThat(stored.text()).isEqualTo("这是包含 MCP 的原文");
        assertThat(board.segments(stored.messageKey())).singleElement().satisfies(segment -> {
            assertThat(segment.type()).isEqualTo(MessageSegment.Type.TEXT);
            assertThat(segment.text()).isEqualTo("这是包含 MCP 的原文");
        });
    }

    @Test
    void richImageOnlyReplyPersistsSegmentAndAllowsEmptyText() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "原消息", "", ""));
        CapturingPort port = new CapturingPort();
        BotReplyService service = new BotReplyService(board, port, CLOCK);

        BotReplyResult result = service.replyRich(GROUP.threadId(), "", null,
                false, false, List.of(new OutboundSegment.Image(
                        "asset-1", "image.png", "image/png")), "image-only");

        assertThat(result.deliveryState()).isEqualTo(DeliveryState.SENT);
        assertThat(result.message().text()).isEmpty();
        assertThat(result.message().nonTextSummary()).contains("[图片×1]");
        assertThat(board.segments(result.message().messageKey()))
                .extracting(MessageSegment::type)
                .containsExactly(MessageSegment.Type.IMAGE);
        assertThat(port.sentAttachments).hasSize(1);
    }

    private static final class CapturingPort implements BotReplyPort {
        private String sentText;
        private List<OutboundSegment> sentAttachments = List.of();

        @Override
        public BotReplySendResult send(ChatSourceKey source, String text,
                                       String replyToMessageId, String idempotencyKey) {
            sentText = text;
            return new BotReplySendResult(DeliveryState.SENT, List.of("qq-1"), "");
        }

        @Override
        public BotReplySendResult sendRich(ChatSourceKey source, String text,
                                           String replyToMessageId, String mentionQqId,
                                           boolean mentionAll,
                                           List<OutboundSegment> attachments,
                                           String idempotencyKey) {
            sentText = text;
            sentAttachments = attachments;
            return new BotReplySendResult(DeliveryState.SENT, List.of("qq-1"), "");
        }
    }
}
