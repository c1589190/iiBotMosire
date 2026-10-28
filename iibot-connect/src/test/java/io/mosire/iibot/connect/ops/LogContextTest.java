package io.mosire.iibot.connect.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LogContextTest {

    @AfterEach
    void clearContext() {
        LogContext.clear();
    }

    @Test
    void traceScopeIsVisibleInsideAndClearedAfterClose() {
        try (LogContext.Scope scope = LogContext.openTrace("inbound:abc123")) {
            assertThat(scope.traceId()).isEqualTo("inbound:abc123");
            assertThat(LogContext.currentTraceId()).isEqualTo("inbound:abc123");
            assertThat(LogContext.currentCallId()).isNull();
        }
        assertThat(LogContext.currentTraceId()).isNull();
        assertThat(LogContext.currentCallId()).isNull();
        assertThat(LogContext.currentBootId()).isNull();
    }

    @Test
    void nestedScopesRestorePreviousValues() {
        try (LogContext.Scope outer = LogContext.open("outer:trace", "call-outer")) {
            try (LogContext.Scope inner = LogContext.openTrace("inner:trace")) {
                assertThat(LogContext.currentTraceId()).isEqualTo("inner:trace");
                assertThat(LogContext.currentCallId()).isEqualTo("call-outer");
            }
            assertThat(LogContext.currentTraceId()).isEqualTo("outer:trace");
            assertThat(LogContext.currentCallId()).isEqualTo("call-outer");
        }
        assertThat(LogContext.currentTraceId()).isNull();
        assertThat(LogContext.currentCallId()).isNull();
    }

    @Test
    void generatedIdsAreSingleLineTokens() {
        String bootId = LogContext.newBootId();
        String traceId = LogContext.newTraceId("Outbound Send");
        String callId = LogContext.newCallId();

        assertThat(bootId).matches("boot-[0-9a-f]{12}");
        assertThat(traceId).matches("outbound_send:[0-9a-f]{12}");
        assertThat(callId).matches("call-[0-9a-f]{12}");
        assertThat(bootId + traceId + callId).doesNotContain(" ", "\n", "\r", "\t");
    }

    @Test
    void toolCallScopeUsesMatchingTraceAndCallSuffix() {
        try (LogContext.Scope scope = LogContext.openToolCall()) {
            assertThat(scope.traceId()).startsWith("tool:");
            assertThat(scope.callId()).startsWith("call-");
            assertThat(scope.traceId()).isEqualTo("tool:" + scope.callId().substring("call-".length()));
            assertThat(LogContext.currentTraceId()).isEqualTo(scope.traceId());
            assertThat(LogContext.currentCallId()).isEqualTo(scope.callId());
        }
        assertThat(LogContext.currentTraceId()).isNull();
        assertThat(LogContext.currentCallId()).isNull();
    }

    @Test
    void bootScopeDoesNotReplaceTraceOrCall() {
        try (LogContext.Scope trace = LogContext.open("startup:one", "call-one")) {
            try (LogContext.Scope boot = LogContext.openBoot("boot-123")) {
                assertThat(LogContext.currentBootId()).isEqualTo("boot-123");
                assertThat(LogContext.currentTraceId()).isEqualTo("startup:one");
                assertThat(LogContext.currentCallId()).isEqualTo("call-one");
            }
            assertThat(LogContext.currentBootId()).isNull();
            assertThat(LogContext.currentTraceId()).isEqualTo("startup:one");
        }
    }

    @Test
    void closedScopeCannotLeakIntoLaterExecutorTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (LogContext.Scope ignored = LogContext.openTrace("inbound:leak-check")) {
                assertThat(LogContext.currentTraceId()).isEqualTo("inbound:leak-check");
            }
            Future<String> traceInTask = executor.submit(LogContext::currentTraceId);
            assertThat(traceInTask.get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scopeCloseIsIdempotent() {
        LogContext.Scope scope = LogContext.openTrace("inbound:once");
        scope.close();
        assertThat(LogContext.currentTraceId()).isNull();
        scope.close();
        assertThat(LogContext.currentTraceId()).isNull();
    }

    @Test
    void unsafeValuesAreSanitizedIntoOneGrepField() {
        try (LogContext.Scope ignored = LogContext.openTrace("inbound:ok\nAuthorization Bearer x")) {
            assertThat(LogContext.currentTraceId())
                    .doesNotContain(" ", "\n", "\r", "\t")
                    .startsWith("inbound:ok_Authorization_Bearer_x");
        }
    }
}
