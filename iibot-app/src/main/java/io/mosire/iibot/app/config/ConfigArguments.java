package io.mosire.iibot.app.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 通用配置源 CLI 解析：支持一个或多个 --config，省略时使用调用方给的默认目录。 */
public final class ConfigArguments {

    private ConfigArguments() {
    }

    public static ParseResult parse(String[] args, Path defaultSource) {
        Objects.requireNonNull(defaultSource, "defaultSource");
        List<Path> sources = new ArrayList<>();
        boolean help = false;
        boolean explicitConfig = false;
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
                throw new ConfigException("未知参数: " + arg);
            }
        }
        if (sources.isEmpty()) {
            sources.add(defaultSource);
        }
        return new ParseResult(List.copyOf(sources), help, explicitConfig);
    }

    /** 去掉兼容用的 --mode 参数；模式本身由 Main 先解析。 */
    public static String[] withoutMode(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--mode".equals(arg)) {
                index++;
                continue;
            }
            if (arg.startsWith("--mode=")) {
                continue;
            }
            result.add(arg);
        }
        return result.toArray(String[]::new);
    }

    public record ParseResult(List<Path> sources, boolean helpRequested,
                              boolean explicitConfig) {
        public ParseResult {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        }
    }
}
