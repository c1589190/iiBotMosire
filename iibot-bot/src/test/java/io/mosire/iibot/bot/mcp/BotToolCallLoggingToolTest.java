package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotToolCallLoggingToolTest {

    private static final String SECRET_ARGUMENT_VALUE = "Bearer very-secret-body";
    private static final String TOOL_LOGGER = BotToolCallLoggingTool.class.getName();

    private Logger opsLogger;
    private Logger toolLogger;
    private Level previousLevel;
    private Level previousToolLevel;
    private CapturingAppender opsAppender;
    private CapturingAppender toolAppender;

    @BeforeEach
    void attachOpsAppender() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        previousLevel = context.getConfiguration().getLoggerConfig("iibot.ops").getLevel();
        previousToolLevel = context.getConfiguration().getLoggerConfig(TOOL_LOGGER).getLevel();
        Configurator.setLevel("iibot.ops", Level.INFO);
        Configurator.setLevel(TOOL_LOGGER, Level.INFO);
        opsLogger = context.getLogger("iibot.ops");
        toolLogger = context.getLogger(TOOL_LOGGER);
        opsAppender = new CapturingAppender("ops-capture");
        toolAppender = new CapturingAppender("tool-capture");
        opsAppender.start();
        toolAppender.start();
        opsLogger.addAppender(opsAppender);
        toolLogger.addAppender(toolAppender);
    }

    @AfterEach
    void detachOpsAppender() {
        opsLogger.removeAppender(opsAppender);
        toolLogger.removeAppender(toolAppender);
        opsAppender.stop();
        toolAppender.stop();
        Configurator.setLevel("iibot.ops", previousLevel);
        Configurator.setLevel(TOOL_LOGGER, previousToolLevel);
        LogContext.clear();
    }

    @Test
    void logsOkAndErrorWithoutArgumentValuesAndWithCorrelationIds() {
        BotToolCallLoggingTool ok = new BotToolCallLoggingTool(tool("safe_tool",
                context -> ToolResult.ok("结果正文不应进入日志")));
        BotToolCallLoggingTool rejected = new BotToolCallLoggingTool(tool("bad_tool",
                context -> ToolResult.error("SAFE_CODE", "错误正文不应进入日志")));

        assertThat(ok.execute(contextWithSecret())).isNotNull();
        assertThat(rejected.execute(contextWithSecret())).isNotNull();

        assertThat(opsAppender.messages).anySatisfy(message -> {
            assertThat(message).contains("event=mcp_tool_call", "tool=safe_tool", "outcome=ok",
                    "argKeys=[secretArg]", "traceId=tool:", "callId=call-");
        }).anySatisfy(message -> {
            assertThat(message).contains("event=mcp_tool_call", "tool=bad_tool",
                    "outcome=error", "code=SAFE_CODE", "traceId=tool:", "callId=call-");
        });
        assertThat(opsAppender.messages).allSatisfy(message -> {
            assertThat(message).doesNotContain("very-secret-body");
            assertThat(message).doesNotContain("Bearer");
            assertThat(message).doesNotContain("结果正文不应进入日志");
            assertThat(message).doesNotContain("错误正文不应进入日志");
        });
        assertThat(LogContext.currentTraceId()).isNull();
    }

    @Test
    void rethrowsOriginalExceptionAndLogsOnlyType() {
        BotToolCallLoggingTool failing = new BotToolCallLoggingTool(tool("explode",
                context -> {
                    throw new IllegalStateException("异常正文不应进入日志");
                }));

        assertThatThrownBy(() -> failing.execute(contextWithSecret()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("异常正文不应进入日志");
        assertThat(opsAppender.messages).singleElement().satisfies(message -> {
            assertThat(message).contains("event=mcp_tool_call", "tool=explode",
                    "outcome=exception", "errorType=IllegalStateException");
            assertThat(message).doesNotContain("异常正文不应进入日志");
            assertThat(message).doesNotContain("very-secret-body");
        });
        assertThat(LogContext.currentTraceId()).isNull();
    }

    @Test
    void debugStartLineAppearsOnlyAfterLoggerRaisedToDebug() {
        BotToolCallLoggingTool tool = new BotToolCallLoggingTool(
                tool("debug_tool", context -> ToolResult.ok("ok")));

        assertThat(tool.execute(contextWithSecret())).isNotNull();
        assertThat(toolAppender.messages)
                .noneMatch(message -> message.contains("event=mcp_tool_call_started"));

        Configurator.setLevel(TOOL_LOGGER, Level.DEBUG);
        assertThat(tool.execute(contextWithSecret())).isNotNull();

        assertThat(toolAppender.messages).anySatisfy(message ->
                assertThat(message).contains("event=mcp_tool_call_started",
                        "tool=debug_tool", "argKeys=[secretArg]", "callId=call-"));
        assertThat(toolAppender.messages).allSatisfy(message -> {
            assertThat(message).doesNotContain("very-secret-body");
            assertThat(message).doesNotContain("Bearer");
        });
    }

    private static AgentTool tool(String name,
                                  java.util.function.Function<ToolContext, ToolResult> action) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return action.apply(context);
            }
        };
    }

    private static ToolContext contextWithSecret() {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), Map.of("secretArg", SECRET_ARGUMENT_VALUE), AgentIdentity.external());
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private CapturingAppender(String name) {
            super(name, null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}
