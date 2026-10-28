package io.mosire.iibot.app.logging;

import io.mosire.iibot.app.bot.BotConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void packagedXmlDeclaresSystemAndEnvironmentFallbacks() throws Exception {
        String xml = readLog4j2Xml();

        assertThat(xml).contains(
                "${sys:iibot.log.dir:-${env:IIBOT_LOG_DIR:-logs}}",
                "${sys:iibot.log.level:-${env:IIBOT_LOG_LEVEL:-INFO}}",
                "${sys:iibot.ops.log.level:-${env:IIBOT_OPS_LOG_LEVEL:-INFO}}",
                "${sys:iibot.log.maxSize:-${env:IIBOT_LOG_MAX_SIZE:-20 MB}}",
                "${sys:iibot.log.maxHistory:-${env:IIBOT_LOG_MAX_HISTORY:-30}}",
                "traceId=%X{traceId}",
                "filePermissions=\"rw-------\"");
    }

    @Test
    void systemPropertiesControlRootAndOpsLevels() throws Exception {
        withConfiguration("debug-levels", "DEBUG", "DEBUG", "20 MB", "30", configuration -> {
            assertThat(configuration.getRootLogger().getLevel()).isEqualTo(Level.DEBUG);
            assertThat(configuration.getLoggerConfig("iibot.ops").getLevel())
                    .isEqualTo(Level.DEBUG);
            assertThat(tempDir.resolve("logs-debug-levels/iibot.log")).exists();
            assertThat(tempDir.resolve("logs-debug-levels/iibot-ops.log")).exists();
        });
        withConfiguration("info-levels", "INFO", "INFO", "20 MB", "30", configuration -> {
            assertThat(configuration.getRootLogger().getLevel()).isEqualTo(Level.INFO);
            assertThat(configuration.getLoggerConfig("iibot.ops").getLevel())
                    .isEqualTo(Level.INFO);
        });
    }

    @Test
    void systemPropertiesControlRollingSizeAndHistory() throws Exception {
        withConfiguration("rolling", "INFO", "INFO", "5 MB", "7", configuration -> {
            RollingFileAppender appender = configuration.getAppender("appFile");
            assertThat(appender).isNotNull();
            CompositeTriggeringPolicy policies =
                    (CompositeTriggeringPolicy) appender.getTriggeringPolicy();
            SizeBasedTriggeringPolicy sizePolicy = Arrays
                    .stream(policies.getTriggeringPolicies())
                    .filter(SizeBasedTriggeringPolicy.class::isInstance)
                    .map(SizeBasedTriggeringPolicy.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(sizePolicy.getMaxFileSize()).isEqualTo(5L * 1024L * 1024L);
            DefaultRolloverStrategy rollover =
                    (DefaultRolloverStrategy) appender.getManager().getRolloverStrategy();
            assertThat(rollover.getMaxIndex()).isEqualTo(7);
        });
    }


    @Test
    void jsonLoggingBlockActuallyChangesRootOpsAndPackageLoggerConfigs() throws Exception {
        Path logDir = tempDir.resolve("logs-json");
        BotConfig.Logging logging = new BotConfig.Logging(logDir, true, true,
                "TRACE", "WARN", Map.of(
                        "io.mosire.iibot.connect", "DEBUG",
                        "io.mosire.iibot.bot", "ERROR",
                        "io.mosire.iibot.app", "INFO"),
                "5MB", 7);

        LoggingConfigurer.apply(logging, null);

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration configuration =
                context.getConfiguration();
        assertThat(configuration.getRootLogger().getLevel()).isEqualTo(Level.TRACE);
        assertThat(configuration.getLoggerConfig("iibot.ops").getLevel())
                .isEqualTo(Level.WARN);
        assertThat(configuration.getLoggerConfig("io.mosire.iibot.connect").getLevel())
                .isEqualTo(Level.DEBUG);
        assertThat(configuration.getLoggerConfig("io.mosire.iibot.bot").getLevel())
                .isEqualTo(Level.ERROR);
        assertThat(configuration.getLoggerConfig("io.mosire.iibot.app").getLevel())
                .isEqualTo(Level.INFO);

        RollingFileAppender appender = configuration.getAppender("appFile");
        CompositeTriggeringPolicy policies =
                (CompositeTriggeringPolicy) appender.getTriggeringPolicy();
        SizeBasedTriggeringPolicy sizePolicy = Arrays.stream(policies.getTriggeringPolicies())
                .filter(SizeBasedTriggeringPolicy.class::isInstance)
                .map(SizeBasedTriggeringPolicy.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(sizePolicy.getMaxFileSize()).isEqualTo(5L * 1024L * 1024L);
        DefaultRolloverStrategy rollover =
                (DefaultRolloverStrategy) appender.getManager().getRolloverStrategy();
        assertThat(rollover.getMaxIndex()).isEqualTo(7);
    }

    @Test
    void cliLogLevelOverridesRootAndOpsButNotExplicitPackageLevels() {
        BotConfig.Logging logging = new BotConfig.Logging(
                tempDir.resolve("logs-cli"), false, false,
                "INFO", "INFO", Map.of("io.mosire.iibot.connect", "INFO"),
                "20MB", 30);

        LoggingConfigurer.apply(logging, "DEBUG");

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration configuration =
                context.getConfiguration();
        assertThat(configuration.getRootLogger().getLevel()).isEqualTo(Level.DEBUG);
        assertThat(configuration.getLoggerConfig("iibot.ops").getLevel())
                .isEqualTo(Level.DEBUG);
        assertThat(configuration.getLoggerConfig("io.mosire.iibot.connect").getLevel())
                .isEqualTo(Level.INFO);
    }


    @Test
    void jsonLoggingConsoleAndFileFlagsRemoveActualAppenderRefs() {
        BotConfig.Logging logging = new BotConfig.Logging(
                tempDir.resolve("logs-disabled"), false, false,
                "INFO", "INFO", Map.of(), "20MB", 30);

        LoggingConfigurer.apply(logging, null);

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration configuration =
                context.getConfiguration();
        assertThat(configuration.getRootLogger().getAppenders())
                .doesNotContainKeys("console", "appFile");
        assertThat(configuration.getLoggerConfig("iibot.ops").getAppenders())
                .doesNotContainKeys("opsConsole", "opsFile");
    }

    private void withConfiguration(String label, String rootLevel, String opsLevel,
                                   String maxSize, String maxHistory,
                                   ConfigurationAssertion assertion) throws Exception {
        String oldDir = System.getProperty("iibot.log.dir");
        String oldRoot = System.getProperty("iibot.log.level");
        String oldOps = System.getProperty("iibot.ops.log.level");
        String oldMaxSize = System.getProperty("iibot.log.maxSize");
        String oldMaxHistory = System.getProperty("iibot.log.maxHistory");
        System.setProperty("iibot.log.dir", tempDir.resolve("logs-" + label).toString());
        System.setProperty("iibot.log.level", rootLevel);
        System.setProperty("iibot.ops.log.level", opsLevel);
        System.setProperty("iibot.log.maxSize", maxSize);
        System.setProperty("iibot.log.maxHistory", maxHistory);
        try (InputStream stream = getClass().getResourceAsStream("/log4j2.xml")) {
            assertThat(stream).isNotNull();
            LoggerContext context = new LoggerContext("iibot-logging-config-" + label);
            XmlConfiguration configuration = new XmlConfiguration(context,
                    new ConfigurationSource(stream));
            context.start(configuration);
            try {
                assertion.accept(configuration);
            } finally {
                context.stop();
            }
        } finally {
            restoreProperty("iibot.log.dir", oldDir);
            restoreProperty("iibot.log.level", oldRoot);
            restoreProperty("iibot.ops.log.level", oldOps);
            restoreProperty("iibot.log.maxSize", oldMaxSize);
            restoreProperty("iibot.log.maxHistory", oldMaxHistory);
        }
    }

    private static String readLog4j2Xml() throws Exception {
        try (InputStream stream = LoggingConfigurationTest.class
                .getResourceAsStream("/log4j2.xml")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @FunctionalInterface
    private interface ConfigurationAssertion {
        void accept(XmlConfiguration configuration) throws Exception;
    }
}
