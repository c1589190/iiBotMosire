package io.mosire.iibot.app.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 图片资产备注：按 assetId 持久化，最多 2000 字符，文件 0600。 */
public final class BotMediaNoteStore {

    public static final int MAX_NOTE_CHARS = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() { };

    private final Path file;
    private final Map<String, String> notes = new ConcurrentHashMap<>();

    public BotMediaNoteStore(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        this.file = dataDir.toAbsolutePath().normalize().resolve("media").resolve("notes.json");
        try {
            Files.createDirectories(file.getParent());
            secure(file.getParent(), true);
            if (Files.isRegularFile(file)) {
                Map<String, String> loaded = JSON.readValue(file.toFile(), TYPE);
                if (loaded != null) {
                    loaded.forEach((key, value) -> {
                        if (key != null && !key.isBlank() && value != null
                                && value.length() <= MAX_NOTE_CHARS) {
                            notes.put(key, value);
                        }
                    });
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("读取图片备注失败", failure);
        }
    }

    public Optional<String> get(String assetId) {
        return Optional.ofNullable(assetId == null ? null : notes.get(assetId));
    }

    public Optional<String> put(String assetId, String note) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId 不能为空");
        }
        String normalized = note == null ? "" : note.trim();
        if (normalized.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("note 最多 " + MAX_NOTE_CHARS + " 字符");
        }
        String previous = notes.get(assetId);
        if (normalized.isEmpty()) {
            notes.remove(assetId);
        } else {
            notes.put(assetId, normalized);
        }
        persist();
        return Optional.ofNullable(previous);
    }

    private synchronized void persist() {
        try {
            Path temporary = Files.createTempFile(file.getParent(), ".notes-", ".tmp");
            try {
                JSON.writeValue(temporary.toFile(), notes);
                secure(temporary, false);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            secure(file, false);
        } catch (IOException failure) {
            throw new IllegalStateException("保存图片备注失败", failure);
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
