package io.mosire.iibot.app.media;

import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.iibot.connect.core.MediaAsset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受控图片资产库：文件落在 dataDir/media，MCP 只拿 assetId，不拿 URL/字节。
 */
public final class BotMediaStore implements ToolAssetResolver {

    private final Path directory;
    private final Map<String, ToolAsset> cache = new ConcurrentHashMap<>();

    public BotMediaStore(Path dataDir) {
        this.directory = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize().resolve("media");
        try {
            Files.createDirectories(directory);
            secure(directory, true);
        } catch (IOException failure) {
            throw new IllegalStateException("创建媒体目录失败", failure);
        }
    }

    /** 落库 inbound 图片资产。 */
    public String store(MediaAsset asset) {
        return storeWithPrefix(asset, "img_");
    }

    /** 落库 Agent 上传的任意出站资产；调用方拿 assetRef，不拿路径/URL。 */
    public String storeUpload(MediaAsset asset) {
        return storeWithPrefix(asset, "asset_");
    }

    private String storeWithPrefix(MediaAsset asset, String prefix) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(prefix, "prefix");
        String hash = sha256(asset.bytes());
        String assetId = prefix + hash;
        Path target = directory.resolve(assetId + extension(asset.fileName(), asset.mediaType()));
        try {
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(directory, ".media-", ".tmp");
                try {
                    Files.write(temporary, asset.bytes());
                    secure(temporary, false);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            secure(target, false);
            cache.put(assetId, new ToolAsset(asset.mediaType(), asset.bytes()));
            Files.writeString(directory.resolve(assetId + ".type"), asset.mediaType());
            return assetId;
        } catch (IOException failure) {
            throw new IllegalStateException("保存媒体资产失败", failure);
        }
    }

    @Override
    public Optional<ToolAsset> resolve(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return Optional.empty();
        }
        ToolAsset cached = cache.get(assetId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path meta = directory.resolve(assetId + ".type");
        try {
            if (!Files.isRegularFile(meta)) {
                return Optional.empty();
            }
            String mediaType = Files.readString(meta).trim();
            var file = findAssetFile(assetId);
            if (file.isEmpty()) {
                return Optional.empty();
            }
            ToolAsset asset = new ToolAsset(mediaType, Files.readAllBytes(file.orElseThrow()));
            cache.put(assetId, asset);
            return Optional.of(asset);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Optional<Path> findAssetFile(String assetId) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(assetId + "."))
                    .filter(path -> !path.getFileName().toString().endsWith(".type"))
                    .findFirst();
        }
    }

    private static String extension(String fileName, String mediaType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < lower.length() && lower.substring(dot + 1).length() <= 8) {
            return lower.substring(dot);
        }
        return switch (mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav" -> ".wav";
            case "audio/ogg", "audio/opus" -> ".ogg";
            case "audio/amr" -> ".amr";
            case "audio/silk" -> ".silk";
            default -> ".jpg";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static void secure(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统尽力而为。
        }
    }
}
