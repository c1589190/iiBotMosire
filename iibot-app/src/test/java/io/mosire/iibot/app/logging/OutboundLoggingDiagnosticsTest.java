package io.mosire.iibot.app.logging;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundLoggingDiagnosticsTest {

    private static final String SECRET_BODY =
            "聊天正文不应进入日志 Authorization: Bearer test-token-9f3a";
    private static final String DELIVERY_LOGGER = OutboxSender.class.getName();

    private Logger opsLogger;
    private Logger deliveryLogger;
    private Level previousOpsLevel;
    private Level previousDeliveryLevel;
    private CapturingAppender opsAppender;
    private CapturingAppender deliveryAppender;

    @BeforeEach
    void attachAppenders() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        previousOpsLevel = context.getConfiguration().getLoggerConfig("iibot.ops").getLevel();
        previousDeliveryLevel = context.getConfiguration()
                .getLoggerConfig(DELIVERY_LOGGER).getLevel();
        Configurator.setLevel("iibot.ops", Level.INFO);
        Configurator.setLevel(DELIVERY_LOGGER, Level.DEBUG);
        opsLogger = context.getLogger("iibot.ops");
        deliveryLogger = context.getLogger(DELIVERY_LOGGER);
        opsAppender = new CapturingAppender("outbound-ops-capture");
        deliveryAppender = new CapturingAppender("outbound-delivery-capture");
        opsAppender.start();
        deliveryAppender.start();
        opsLogger.addAppender(opsAppender);
        deliveryLogger.addAppender(deliveryAppender);
    }

    @AfterEach
    void detachAppenders() {
        opsLogger.removeAppender(opsAppender);
        deliveryLogger.removeAppender(deliveryAppender);
        opsAppender.stop();
        deliveryAppender.stop();
        Configurator.setLevel("iibot.ops", previousOpsLevel);
        Configurator.setLevel(DELIVERY_LOGGER, previousDeliveryLevel);
        LogContext.clear();
    }

    @Test
    void outboundLogsCorrelationButNeverChatBodyOrToken() {
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        OutboxSender outbox = new OutboxSender(adapter, Duration.ofSeconds(2));

        outbox.sendPrivateToUser("qq-1", List.of(SECRET_BODY), "diag-1");

        assertThat(adapter.sent()).hasSize(1);
        assertThat(opsAppender.messages).anySatisfy(message -> assertThat(message)
                .contains("event=outbound_send", "state=SENT", "traceId=outbound:",
                        "key=private:diag-1:reply:0"));
        assertThat(deliveryAppender.messages).anySatisfy(message -> assertThat(message)
                .contains("event=outbound_route", "targetType=PRIVATE", "targetId=qq-1",
                        "key=private:diag-1:reply:0"));
        assertThat(deliveryAppender.traceIds)
                .anySatisfy(traceId -> assertThat(traceId).startsWith("outbound:"));
        assertThat(allCapturedMessages()).allSatisfy(message -> {
            assertThat(message).doesNotContain(SECRET_BODY);
            assertThat(message).doesNotContain("test-token-9f3a");
            assertThat(message).doesNotContain("Authorization");
            assertThat(message).doesNotContain("Bearer");
        });
        assertThat(LogContext.currentTraceId()).isNull();
        assertThat(LogContext.currentCallId()).isNull();
        assertThat(LogContext.currentBootId()).isNull();
    }

    private List<String> allCapturedMessages() {
        List<String> all = new ArrayList<>(opsAppender.messages);
        all.addAll(deliveryAppender.messages);
        return all;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();
        private final List<String> traceIds = new ArrayList<>();

        private CapturingAppender(String name) {
            super(name, null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
            Object traceId = event.getContextData().getValue(LogContext.TRACE_ID);
            traceIds.add(traceId == null ? null : traceId.toString());
        }
    }
}
