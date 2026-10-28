package io.mosire.iibot.connect.ops;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OpsLogTest {

    private Logger opsLogger;
    private Level previousLevel;
    private CapturingAppender appender;

    @BeforeEach
    void attachAppender() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        previousLevel = context.getConfiguration().getLoggerConfig("iibot.ops").getLevel();
        Configurator.setLevel(OpsLog.LOGGER_NAME, Level.INFO);
        opsLogger = context.getLogger(OpsLog.LOGGER_NAME);
        appender = new CapturingAppender();
        appender.start();
        opsLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        opsLogger.removeAppender(appender);
        appender.stop();
        Configurator.setLevel(OpsLog.LOGGER_NAME, previousLevel);
        LogContext.clear();
    }

    @Test
    void debugIsHiddenAtInfoAndVisibleAfterLevelRaised() {
        OpsLog.debug("event=debug_hidden");
        OpsLog.info("event=info_visible");

        assertThat(appender.messages).containsExactly("event=info_visible");

        Configurator.setLevel(OpsLog.LOGGER_NAME, Level.DEBUG);
        OpsLog.debug("event=debug_visible");

        assertThat(appender.messages)
                .containsExactly("event=info_visible", "event=debug_visible")
                .doesNotContain("event=debug_hidden");
    }

    @Test
    void traceAndCallIdsAreAppendedInsideScopeAndAbsentAfterClose() {
        try (LogContext.Scope ignored = LogContext.open("inbound:abc", "call-123")) {
            OpsLog.info("event=inside");
        }
        OpsLog.info("event=outside");

        assertThat(appender.messages.getFirst())
                .contains("event=inside", "traceId=inbound:abc", "callId=call-123");
        assertThat(appender.traceIds).containsExactly("inbound:abc", null);
        assertThat(appender.messages.getLast())
                .contains("event=outside")
                .doesNotContain("traceId=", "callId=");
    }

    @Test
    void explicitTraceIdIsNotDuplicated() {
        try (LogContext.Scope ignored = LogContext.openTrace("inbound:from-context")) {
            OpsLog.info("event=explicit traceId=inbound:given");
        }

        assertThat(appender.messages).singleElement().satisfies(message ->
                assertThat(message).contains("traceId=inbound:given")
                        .doesNotContain("traceId=inbound:from-context"));
    }

    @Test
    void loggingFailureNeverChangesBusinessResult() {
        assertThatCode(() -> OpsLog.emit(() -> {
            throw new IllegalStateException("日志实现异常不应冒泡");
        })).doesNotThrowAnyException();

        assertThat(businessActionWithFailingLog()).isEqualTo("business-ok");
    }

    private static String businessActionWithFailingLog() {
        OpsLog.emit(() -> {
            throw new IllegalStateException("boom");
        });
        return "business-ok";
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();
        private final List<String> traceIds = new ArrayList<>();

        private CapturingAppender() {
            super("ops-log-test-capture", null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
            Object traceId = event.getContextData().getValue(LogContext.TRACE_ID);
            traceIds.add(traceId == null ? null : traceId.toString());
        }
    }
}
