package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 首次无配置启动时生成单一完整 bot 配置模板。
 *
 * <p>只有默认配置目录没有任何 {@code *.json} 时才生成 {@code bot.json}；已有文件绝不
 * 覆盖。生成文件权限 0600、目录权限 0700，内容只包含环境变量名。调用方收到
 * {@code generated=true} 后应输出下一步提示并退出 rc=2，不启动 QQ/Inbox/MCP。</p>
 */
public final class BotConfigBootstrap {

    public static final String TEMPLATE_NAME = "bot.json";
    private static final String RESOURCE = "/bot-config-template/bot.json";

    private BotConfigBootstrap() {
    }

    public static Result ensureDefault(Path configDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Path directory = configDirectory.toAbsolutePath().normalize();
        try {
            if (Files.exists(directory) && !Files.isDirectory(directory)) {
                throw ConfigException.invalid(directory, "/", "directory", "file",
                        "默认配置路径不是目录: " + directory);
            }
            if (containsJson(directory)) {
                return new Result(directory, List.of(), false);
            }
            Files.createDirectories(directory);
            secureDirectory(directory);
            Path target = directory.resolve(TEMPLATE_NAME);
            try (InputStream resource = BotConfigBootstrap.class
                    .getResourceAsStream(RESOURCE)) {
                if (resource == null) {
                    throw new ConfigException("内置 bot 配置模板缺失: " + RESOURCE);
                }
                try (OutputStream output = Files.newOutputStream(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    resource.transferTo(output);
                }
            } catch (FileAlreadyExistsException race) {
                // 竞态下另一个进程已生成；绝不覆盖。
                return new Result(directory, List.of(), false);
            }
            secureFile(target);
            return new Result(directory, List.of(target), true);
        } catch (IOException failure) {
            throw ConfigException.invalid(directory, "/", "writable directory",
                    "I/O error", "生成默认 bot 配置失败: " + directory, failure);
        }
    }

    private static boolean containsJson(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".json")
                    && !path.getFileName().toString().startsWith("."));
        }
    }

    private static void secureDirectory(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统尽力而为。
        }
    }

    private static void secureFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统尽力而为；模板不含 raw token。
        }
    }

    public record Result(Path directory, List<Path> files, boolean generated) {
        public Result {
            Objects.requireNonNull(directory, "directory");
            files = List.copyOf(Objects.requireNonNull(files, "files"));
        }
    }
}
