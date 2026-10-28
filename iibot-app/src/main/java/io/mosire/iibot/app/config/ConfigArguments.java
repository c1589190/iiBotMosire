package io.mosire.iibot.app.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 配置/诊断 CLI 参数：--config、--check-config、--print-effective-config、--log-level。 */
public final class ConfigArguments {

    public static final Set<String> LOG_LEVELS =
            Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private ConfigArguments() {
    }

    public static ParseResult parse(String[] args, Path defaultSource) {
        Objects.requireNonNull(defaultSource, "defaultSource");
        List<Path> sources = new ArrayList<>();
        boolean help = false;
        boolean explicitConfig = false;
        boolean checkConfig = false;
        boolean printEffectiveConfig = false;
        String logLevel = null;
        if (args != null) {
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                    continue;
                }
                if ("--config".equals(arg)) {
                    if (index + 1 >= args.length || args[index + 1].isBlank()
                            || args[index + 1].startsWith("--")) {
                        throw new ConfigException("--config 需要一个路径参数");
                    }
                    sources.add(Path.of(args[++index]));
                    explicitConfig = true;
                    continue;
                }
                if (arg.startsWith("--config=")) {
                    String value = arg.substring("--config=".length()).trim();
                    if (value.isEmpty()) {
                        throw new ConfigException("--config 需要一个路径参数");
                    }
                    sources.add(Path.of(value));
                    explicitConfig = true;
                    continue;
                }
                if ("--check-config".equals(arg)) {
                    checkConfig = true;
                    continue;
                }
                if ("--print-effective-config".equals(arg)) {
                    printEffectiveConfig = true;
                    continue;
                }
                if ("--log-level".equals(arg)) {
                    if (index + 1 >= args.length || args[index + 1].isBlank()
                            || args[index + 1].startsWith("--")) {
                        throw new ConfigException("--log-level 需要一个级别参数");
                    }
                    logLevel = normalizeLevel(args[++index]);
                    continue;
                }
                if (arg.startsWith("--log-level=")) {
                    String value = arg.substring("--log-level=".length()).trim();
                    if (value.isEmpty()) {
                        throw new ConfigException("--log-level 需要一个级别参数");
                    }
                    logLevel = normalizeLevel(value);
                    continue;
                }
                throw new ConfigException("未知参数: " + arg);
            }
        }
        if (checkConfig && printEffectiveConfig) {
            throw new ConfigException(
                    "--check-config 与 --print-effective-config 不能同时使用");
        }
        if (sources.isEmpty()) {
            sources.add(defaultSource);
        }
        return new ParseResult(List.copyOf(sources), help, explicitConfig,
                checkConfig, printEffectiveConfig, Optional.ofNullable(logLevel));
    }

    private static String normalizeLevel(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!LOG_LEVELS.contains(normalized)) {
            throw new ConfigException("--log-level 必须是 TRACE/DEBUG/INFO/WARN/ERROR/OFF");
        }
        return normalized;
    }

    public record ParseResult(List<Path> sources, boolean helpRequested,
                              boolean explicitConfig, boolean checkConfig,
                              boolean printEffectiveConfig,
                              Optional<String> logLevel) {
        public ParseResult {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            logLevel = Objects.requireNonNull(logLevel, "logLevel");
        }

        public boolean diagnosticRequested() {
            return checkConfig || printEffectiveConfig;
        }
    }
}
