package io.mosire.iibot.app.logging;

import io.mosire.iibot.app.bot.BotConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 在首个业务 Logger 初始化前应用 JSON {@code logging} 块和可选 CLI 级别。
 *
 * <p>目录/大小/保留数通过 log4j2 系统属性让 XML 重新构建；root/ops/包级级别通过
 * Log4j Configurator 写入当前 LoggerConfig。{@code --log-level} 只覆盖 root 与 ops，
 * 且优先级最高。任何目录创建失败都 fail-closed。</p>
 */
public final class LoggingConfigurer {

    private LoggingConfigurer() {
    }

    public static Applied apply(BotConfig.Logging logging, String cliLogLevel) {
        Objects.requireNonNull(logging, "logging");
        String override = cliLogLevel == null || cliLogLevel.isBlank()
                ? null : normalizeLevel(cliLogLevel);
        String rootLevel = override == null ? logging.rootLevel() : override;
        String opsLevel = override == null ? logging.opsLevel() : override;

        if (logging.file()) {
            ensureDirectory(logging.directory());
        }
        System.setProperty("iibot.log.dir", logging.directory().toString());
        System.setProperty("iibot.log.level", rootLevel);
        System.setProperty("iibot.ops.log.level", opsLevel);
        System.setProperty("iibot.log.maxSize", logging.maxFileSize());
        System.setProperty("iibot.log.maxHistory", Integer.toString(logging.maxHistory()));

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.reconfigure();
        Configuration configuration = context.getConfiguration();

        for (Map.Entry<String, String> entry : logging.levels().entrySet()) {
            Configurator.setLevel(entry.getKey(), toLevel(entry.getValue()));
        }
        // 最终再写 root/ops，确保 --log-level 与显式 root/ops 配置拥有最高优先级。
        Configurator.setRootLevel(toLevel(rootLevel));
        Configurator.setLevel("iibot.ops", toLevel(opsLevel));

        LoggerConfig root = configuration.getRootLogger();
        LoggerConfig ops = configuration.getLoggerConfig("iibot.ops");
        if (!logging.console()) {
            root.removeAppender("console");
            ops.removeAppender("opsConsole");
        }
        if (!logging.file()) {
            root.removeAppender("appFile");
            ops.removeAppender("opsFile");
        }
        return new Applied(logging.directory(), rootLevel, opsLevel,
                logging.console(), logging.file(), logging.levels(),
                logging.maxFileSize(), logging.maxHistory());
    }

    private static Level toLevel(String level) {
        return switch (normalizeLevel(level)) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            case "OFF" -> Level.OFF;
            default -> throw new IllegalArgumentException("非法日志级别: " + level);
        };
    }

    private static String normalizeLevel(String level) {
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        if (!BotConfig.Logging.LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("非法日志级别: " + level);
        }
        return normalized;
    }

    private static void ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            try {
                Files.setPosixFilePermissions(directory, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统尽力而为。
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "日志目录不可创建或不可写: " + directory, failure);
        }
    }

    public record Applied(Path directory, String rootLevel, String opsLevel,
                          boolean console, boolean file, Map<String, String> levels,
                          String maxFileSize, int maxHistory) {
        public Applied {
            levels = Map.copyOf(levels);
        }
    }
}
