package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 首次无配置启动时生成 bot 配置模板。
 *
 * <p>只有默认配置目录完全没有任何 *.json 时才生成；已有配置绝不覆盖。
 * 生成后调用方应提示用户编辑并退出，不启动 QQ/Inbox/MCP。</p>
 */
public final class BotConfigBootstrap {

    private static final List<String> TEMPLATE_NAMES = List.of(
            "00-base.json",
            "10-napcat.json",
            "20-control-inbox.json",
            "30-policy.json");
    private static final String RESOURCE_ROOT = "/bot-config-template/";

    private BotConfigBootstrap() {
    }

    public static Result ensureDefault(Path configDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Path directory = configDirectory.toAbsolutePath().normalize();
        try {
            if (Files.exists(directory) && !Files.isDirectory(directory)) {
                throw new ConfigException("默认配置路径不是目录: " + directory);
            }
            if (containsJson(directory)) {
                return new Result(directory, List.of(), false);
            }
            Files.createDirectories(directory);
            List<Path> generated = new ArrayList<>();
            for (String name : TEMPLATE_NAMES) {
                Path target = directory.resolve(name);
                try (InputStream resource = BotConfigBootstrap.class
                        .getResourceAsStream(RESOURCE_ROOT + name)) {
                    if (resource == null) {
                        throw new ConfigException(
                                "内置 bot 配置模板缺失: " + RESOURCE_ROOT + name);
                    }
                    Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);
                }
                secure(target);
                generated.add(target);
            }
            return new Result(directory, List.copyOf(generated), true);
        } catch (IOException failure) {
            throw new ConfigException("生成默认 bot 配置失败: " + directory, failure);
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

    private static void secure(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    EnumSet.of(PosixFilePermission.OWNER_READ,
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
