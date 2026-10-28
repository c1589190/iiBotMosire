package io.mosire.iibot.bot;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.inbox.InboxDigest;
import io.mosire.iibot.bot.inbox.InboxDigestConflictException;
import io.mosire.iibot.bot.inbox.InboxDigestItem;
import io.mosire.iibot.bot.inbox.InboxDigestPort;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.inbox.InMemoryInboxDigestStateStore;
import io.mosire.iibot.bot.ingest.BotIngestionService;
import io.mosire.iibot.bot.note.InMemoryUserNoteStore;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.ops.LogContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BotOpsLoggingCoverageTest {

    private static final Instant T0 = Instant.parse("2026-10-27T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final String SECRET_BODY = "运维日志绝不能包含的聊天正文";
    private static final String SECRET_TOKEN = "test-token-5f8a";
    private static final String SECRET_AUTHORIZATION = "Bearer " + SECRET_TOKEN;

    private Logger opsLogger;
    private Level previousLevel;
    private CapturingAppender appender;

    @BeforeEach
    void attachOpsAppender() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        previousLevel = context.getConfiguration().getLoggerConfig("iibot.ops").getLevel();
        Configurator.setLevel("iibot.ops", Level.INFO);
        opsLogger = context.getLogger("iibot.ops");
        appender = new CapturingAppender();
        appender.start();
        opsLogger.addAppender(appender);
    }

    @AfterEach
    void detachOpsAppender() {
        opsLogger.removeAppender(appender);
        appender.stop();
        Configurator.setLevel("iibot.ops", previousLevel);
        LogContext.clear();
    }

    @Test
    void ingestLogsAcceptedAndDuplicateWithoutMessageBody() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        BotIngestionService ingestion = new BotIngestionService(board,
                (BotIngestionService.DigestTrigger) (threadId, delayMillis) -> { }, null);
        InboundMessage message = new InboundMessage("adapter-1", "bot-1", "m-1", "qq-1",
                InboundMessage.SessionType.GROUP, "group-9",
                SECRET_BODY + " " + SECRET_AUTHORIZATION, T0, "dedupe-1");

        ingestion.ingest(message);
        ingestion.ingest(message);

        assertThat(appender.messages).anySatisfy(line -> assertThat(line)
                .contains("event=inbound_ingest", "threadId=" + GROUP.threadId(),
                        "outcome=accepted", "traceId=inbound:"))
                .anySatisfy(line -> assertThat(line)
                        .contains("event=inbound_ingest", "outcome=duplicate",
                                "traceId=inbound:"));
        assertThat(appender.messages).allSatisfy(line -> {
            assertThat(line).doesNotContain(SECRET_BODY);
            assertThat(line).doesNotContain(SECRET_TOKEN);
            assertThat(line).doesNotContain("Authorization");
        });
        assertThat(LogContext.currentTraceId()).isNull();
    }

    @Test
    void digestPublishAndAckAreOperationallyVisible() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        InMemoryInboxDigestStateStore states = new InMemoryInboxDigestStateStore();
        FakePort port = new FakePort();
        InboxDigestService service = new InboxDigestService(board, notes, states, port);
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, SECRET_BODY, "", ""));

        InboxDigestService.FlushResult published = service.flushThread(GROUP.threadId());
        InboxDigestItem item = port.items.get(published.itemId());
        service.markHandled(GROUP.threadId(), published.itemId(), item.revision());

        assertThat(appender.messages).anySatisfy(line -> assertThat(line)
                .contains("event=inbox_digest_published", "threadId=" + GROUP.threadId(),
                        "itemId=" + published.itemId()))
                .anySatisfy(line -> assertThat(line)
                        .contains("event=inbox_ack", "outcome=archived",
                                "itemId=" + published.itemId()));
        assertThat(appender.messages).allSatisfy(line -> {
            assertThat(line).doesNotContain(SECRET_BODY);
            assertThat(line).doesNotContain(SECRET_TOKEN);
            assertThat(line).doesNotContain("Authorization");
        });
    }

    @Test
    void replyLogsStateAndTraceWithoutBodyOrToken() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, SECRET_BODY, "", ""));
        BotReplyService service = new BotReplyService(board,
                (source, text, replyToMessageId, idempotencyKey) ->
                        new BotReplySendResult(DeliveryState.SENT,
                                List.of("out-1"), ""), CLOCK);

        service.reply(GROUP.threadId(), SECRET_BODY + " " + SECRET_AUTHORIZATION,
                null, "reply-secret-1");

        assertThat(appender.messages).anySatisfy(line -> assertThat(line)
                .contains("event=reply_send", "state=SENT", "traceId=reply:"));
        assertThat(appender.messages).allSatisfy(line -> {
            assertThat(line).doesNotContain(SECRET_BODY);
            assertThat(line).doesNotContain(SECRET_TOKEN);
            assertThat(line).doesNotContain("Authorization");
        });
        assertThat(LogContext.currentTraceId()).isNull();
    }


    @Test
    void inboundAccountMismatchIsRejectedWithStableEventAndNoBody() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        BotIngestionService ingestion = new BotIngestionService(board,
                (BotIngestionService.DigestTrigger) (threadId, delayMillis) -> { }, null);
        InboundMessage message = new InboundMessage("adapter-a", "bot-b", "m-mismatch",
                "qq-1", InboundMessage.SessionType.GROUP, "group-9",
                SECRET_BODY + " " + SECRET_AUTHORIZATION, T0, "dedupe-mismatch");

        BotIngestionService.IngestResult result = ingestion.ingest("bot-a", message);

        assertThat(result.accepted()).isFalse();
        assertThat(result.created()).isFalse();
        assertThat(board.threads(10)).isEmpty();
        assertThat(appender.messages).anySatisfy(line -> assertThat(line)
                .contains("event=inbound_account_mismatch", "expectedAccountId=bot-a",
                        "actualAccountId=bot-b", "outcome=rejected",
                        "traceId=inbound:"));
        assertThat(appender.messages).allSatisfy(line -> {
            assertThat(line).doesNotContain(SECRET_BODY);
            assertThat(line).doesNotContain(SECRET_TOKEN);
            assertThat(line).doesNotContain("Authorization");
        });
    }

    private static final class FakePort implements InboxDigestPort {
        private final Map<String, InboxDigestItem> items = new LinkedHashMap<>();
        private long nextItem = 1L;

        @Override
        public InboxDigestItem push(String dedupeKey, InboxDigest digest) {
            String itemId = "itm-" + nextItem++;
            InboxDigestItem item = new InboxDigestItem(
                    itemId, 1L, "available", digest.toSeq(), digest.fromSeq());
            items.put(itemId, item);
            return item;
        }

        @Override
        public Optional<InboxDigestItem> view(String itemId) {
            return Optional.ofNullable(items.get(itemId));
        }

        @Override
        public InboxDigestItem update(String itemId, long expectedRevision,
                                      InboxDigest digest) {
            InboxDigestItem current = items.get(itemId);
            if (current == null || current.revision() != expectedRevision) {
                throw new InboxDigestConflictException("revision conflict");
            }
            InboxDigestItem next = new InboxDigestItem(itemId, current.revision() + 1L,
                    "available", digest.toSeq(), digest.fromSeq());
            items.put(itemId, next);
            return next;
        }

        @Override
        public void archive(String itemId, long expectedRevision) {
            InboxDigestItem current = items.get(itemId);
            if (current == null || current.revision() != expectedRevision) {
                throw new InboxDigestConflictException("archive conflict");
            }
            items.put(itemId, new InboxDigestItem(
                    itemId, current.revision() + 1L, "done", current.toSeq()));
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private CapturingAppender() {
            super("ops-capture", null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}
