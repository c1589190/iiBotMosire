package io.mosire.iibot.app.media;

import io.mosire.iibot.connect.core.QqGroupFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 当前进程内的群文件短期句柄表。
 *
 * <p>fileRef 是由进程随机盐参与的不可逆哈希，不携带原始 fileId/busid。句柄有容量和
 * 时间上限；过期后 Agent 必须重新列目录。</p>
 */
public final class BotGroupFileHandles {

    private static final int MAX_ENTRIES = 1_024;
    private static final Duration TTL = Duration.ofMinutes(10);

    private final byte[] salt;
    private final Map<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);

    public BotGroupFileHandles() {
        salt = new byte[16];
        new SecureRandom().nextBytes(salt);
    }

    synchronized String register(String accountId, String groupId, QqGroupFile file) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(file, "file");
        pruneExpired(Instant.now());
        String ref = hash(accountId, groupId, file);
        entries.put(ref, new Entry(accountId, groupId, file,
                Instant.now().plus(TTL)));
        while (entries.size() > MAX_ENTRIES) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
        return ref;
    }

    synchronized Optional<Entry> resolve(String fileRef) {
        if (fileRef == null || fileRef.isBlank()) {
            return Optional.empty();
        }
        pruneExpired(Instant.now());
        return Optional.ofNullable(entries.get(fileRef.trim()));
    }

    private void pruneExpired(Instant now) {
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String hash(String accountId, String groupId, QqGroupFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update((byte) 0);
            digest.update(accountId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(groupId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.fileId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.busid().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    record Entry(String accountId, String groupId, QqGroupFile file,
                 Instant expiresAt) {
        Entry {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
