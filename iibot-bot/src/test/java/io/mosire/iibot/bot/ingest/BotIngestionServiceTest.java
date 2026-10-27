package io.mosire.iibot.bot.ingest;

import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.InboundMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotIngestionServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void recordsGroupMessageAndTriggersDigestOnlyOnceForDuplicate() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        List<String> triggered = new ArrayList<>();
        BotIngestionService service = new BotIngestionService(board, triggered::add);
        InboundMessage message = new InboundMessage("adapter-1", "bot-1", "m-1", "qq-1",
                "Alice", InboundMessage.SessionType.GROUP, "group-9", "你好",
                "[图片×1]", "reply-1", false, T0, "dedupe-1");

        BotIngestionService.IngestResult first = service.ingest(message);
        BotIngestionService.IngestResult duplicate = service.ingest(message);

        assertThat(first.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(first.message().source().threadId()).isEqualTo(duplicate.message().source().threadId());
        assertThat(first.message().text()).isEqualTo("你好");
        assertThat(first.message().nonTextSummary()).isEqualTo("[图片×1]");
        assertThat(triggered).containsExactly(first.message().source().threadId());
    }

    @Test
    void mapsPrivateMessageToPrivateThread() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        BotIngestionService service = new BotIngestionService(board, ignored -> { });
        InboundMessage message = new InboundMessage("adapter-1", "bot-1", "m-2", "qq-1",
                "Alice", InboundMessage.SessionType.PRIVATE, null, "私聊", "", "", false,
                T0, "dedupe-2");

        BotIngestionService.IngestResult result = service.ingest(message);

        assertThat(result.message().source().chatType().wire()).isEqualTo("private");
        assertThat(board.readThread(result.message().source().threadId(), 0L, 10)).hasSize(1);
    }
}
