package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
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
    }

    @Test
    void logsOkAndErrorWithoutArgumentValues() {
        BotToolCallLoggingTool ok = new BotToolCallLoggingTool(tool("safe_tool",
                context -> ToolResult.ok("结果正文不应进入日志")));
        BotToolCallLoggingTool rejected = new BotToolCallLoggingTool(tool("bad_tool",
                context -> ToolResult.error("SAFE_CODE", "错误正文不应进入日志")));

        assertThat(ok.execute(contextWithSecret())).isNotNull();
        assertThat(rejected.execute(contextWithSecret())).isNotNull();

        assertThat(appender.messages).anySatisfy(message -> {
            assertThat(message).contains("event=mcp_tool_call", "tool=safe_tool", "outcome=ok",
                    "argKeys=[secretArg]");
        }).anySatisfy(message -> {
            assertThat(message).contains("event=mcp_tool_call", "tool=bad_tool",
                    "outcome=error", "code=SAFE_CODE");
        });
        assertThat(appender.messages).allSatisfy(message -> {
            assertThat(message).doesNotContain("very-secret-body");
            assertThat(message).doesNotContain("结果正文不应进入日志");
            assertThat(message).doesNotContain("错误正文不应进入日志");
        });
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
        assertThat(appender.messages).singleElement().satisfies(message -> {
            assertThat(message).contains("event=mcp_tool_call", "tool=explode",
                    "outcome=exception", "errorType=IllegalStateException");
            assertThat(message).doesNotContain("异常正文不应进入日志");
            assertThat(message).doesNotContain("very-secret-body");
        });
    }

    private static AgentTool tool(String name, java.util.function.Function<ToolContext, ToolResult> action) {
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
                Map.of(), Map.of("secretArg", "very-secret-body"), AgentIdentity.external());
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
