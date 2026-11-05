package io.mosire.iibot.app.media;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/**
 * 通用的「下载保存落点」：所有 {@code saveTo} 参数都相对 {@code <dataDir>/downloads}。
 *
 * <p>安全边界：{@code saveTo} 只接受相对路径；绝对路径、盘符和任何含 {@code ..} 的
 * 路径一律拒绝（先 {@link Path#normalize()} 再 {@link Path#startsWith(Path)} 双重校验），
 * 即使将来解析逻辑变化也不会写出根目录。错误消息不携带内部绝对路径。</p>
 *
 * <p>落盘规则：{@code saveTo} 为空落到根目录；以 {@code /} 结尾或指向已存在目录时按
 * 目录处理，并采用调用方给的默认文件名；同名文件已存在时追加 {@code -1}、{@code -2}
 * 去重，绝不覆盖既有文件。</p>
 */
public final class MediaDownloadSink {

    /** {@code saveTo} 的根目录名。 */
    public static final String DIRECTORY_NAME = "downloads";

    private static final int MAX_NAME_LENGTH = 120;

    private final Path root;

    public MediaDownloadSink(Path dataDir) {
        Path base = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize();
        this.root = base.resolve(DIRECTORY_NAME);
        try {
            Files.createDirectories(root);
        } catch (IOException failure) {
            throw new IllegalStateException("创建下载目录失败", failure);
        }
    }

    /** 保存根目录（绝对、已 normalize）。 */
    public Path root() {
        return root;
    }

    /**
     * 只校验 {@code saveTo} 是否合法，不落盘；用于在下载前 fail-fast。
     *
     * @throws InvalidSaveToException 越界或非法路径
     */
    public void validate(String saveTo) {
        resolveTarget(saveTo, "media");
    }

    /**
     * 保存一段字节并返回绝对路径。
     *
     * @param saveTo           相对 downloads 的目录或文件名；空白表示根目录
     * @param fallbackFileName {@code saveTo} 按目录处理时使用的文件名
     * @param bytes            文件内容
     * @return 实际写入的绝对路径（已去重）
     * @throws InvalidSaveToException {@code saveTo} 越界或非法
     */
    public Path save(String saveTo, String fallbackFileName, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        Path target = resolveTarget(saveTo, fallbackFileName);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException failure) {
            throw new IllegalStateException("创建下载目录失败", failure);
        }
        return writeWithoutOverwrite(target, bytes);
    }

    /**
     * 按「消息键 + 段下标」生成默认文件名 {@code qq-<messageKey>-<index>.<ext>}。
     *
     * <p>{@code messageKey} 里的路径分隔符等字符会被替换成 {@code -}，避免文件名带目录
     * 语义。</p>
     */
    public static String defaultFileName(String messageKey, int index, String mediaType) {
        String safe = messageKey == null ? "" : messageKey.replaceAll("[^A-Za-z0-9._-]", "-");
        safe = safe.replaceAll("^[.-]+", "");
        if (safe.length() > MAX_NAME_LENGTH) {
            safe = safe.substring(0, MAX_NAME_LENGTH);
        }
        if (safe.isEmpty()) {
            safe = "media";
        }
        return "qq-" + safe + "-" + index + extensionFor(mediaType);
    }

    /** 常见媒体类型到文件扩展名；未知类型返回空串。 */
    public static String extensionFor(String mediaType) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).trim();
        return switch (type) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/amr" -> ".amr";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/ogg" -> ".ogg";
            case "audio/silk" -> ".silk";
            case "application/pdf" -> ".pdf";
            case "application/zip" -> ".zip";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            default -> "";
        };
    }

    private Path resolveTarget(String saveTo, String fallbackFileName) {
        String raw = saveTo == null ? "" : saveTo.trim();
        if (raw.isEmpty()) {
            return root.resolve(cleanFileName(fallbackFileName, "media"));
        }
        if (hasDriveLetter(raw)) {
            throw new InvalidSaveToException(
                    "saveTo 必须是 downloads 目录内的相对路径，不能包含盘符");
        }
        Path relative;
        try {
            relative = Paths.get(raw);
        } catch (InvalidPathException failure) {
            throw new InvalidSaveToException("saveTo 不是合法路径");
        }
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new InvalidSaveToException(
                    "saveTo 必须是 downloads 目录内的相对路径，不能是绝对路径");
        }
        for (Path component : relative) {
            if ("..".equals(component.toString())) {
                throw new InvalidSaveToException("saveTo 不能包含 ..");
            }
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new InvalidSaveToException("saveTo 越出 downloads 目录");
        }
        boolean directory = raw.endsWith("/") || raw.endsWith("\\")
                || Files.isDirectory(resolved);
        return directory
                ? resolved.resolve(cleanFileName(fallbackFileName, "media"))
                : resolved;
    }

    private static boolean hasDriveLetter(String raw) {
        return raw.length() >= 2
                && Character.isLetter(raw.charAt(0))
                && raw.charAt(1) == ':';
    }

    private static String cleanFileName(String candidate, String fallback) {
        String name = candidate == null ? "" : candidate.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = fallback;
        }
        return name;
    }

    private static Path writeWithoutOverwrite(Path target, byte[] bytes) {
        Path candidate = target;
        for (int suffix = 0; suffix < 10_000; suffix++) {
            try {
                Files.write(candidate, bytes, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return candidate.toAbsolutePath().normalize();
            } catch (FileAlreadyExistsException exists) {
                candidate = withSuffix(target, suffix + 1);
            } catch (IOException failure) {
                throw new IllegalStateException("保存下载文件失败", failure);
            }
        }
        throw new IllegalStateException("保存下载文件失败：同名文件过多");
    }

    private static Path withSuffix(Path target, int suffix) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return target.resolveSibling(base + "-" + suffix + extension);
    }

    /** {@code saveTo} 越界或非法；消息不含内部绝对路径。 */
    public static final class InvalidSaveToException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public InvalidSaveToException(String message) {
            super(message);
        }
    }
}
