package io.mosire.iibot.app.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheException;
import io.mosire.iibot.taskflow.cache.SecretaryCacheStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 基于 SQLite 会话库和每玩家 JSON 索引的秘书会话 cache 实现。
 *
 * <p>消息原文交给 AgentLib {@link SqliteConversationStore} 编码/保存；索引只保存 cache
 * 元数据和 activeCacheId。每个玩家的索引位于独立目录，消息会话 key 也带 playerId，
 * 因此不会把玩家 A 的 cache 解析成玩家 B 的消息。索引采用同目录临时文件 + 原子替换。</p>
 *
 * <p>该实现面向单 JVM 串行装配；同一 cache 的写入由 {@code SecretarySession} 的
 * synchronized 回合边界串行化。关闭时释放 SQLite 连接。</p>
 */
public final class SqliteSecretaryCacheStore implements SecretaryCacheStore, AutoCloseable {

    private static final Pattern CACHE_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final int INDEX_VERSION = 1;
    private static final String INDEX_FILE = "index.json";
    private static final String DB_FILE = "conversations.db";

    private final Path root;
    private final Path dbFile;
    private final ConversationStore conversations;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    private SqliteSecretaryCacheStore(Path root, Path dbFile,
                                      ConversationStore conversations) {
        this.root = root;
        this.dbFile = dbFile;
        this.conversations = conversations;
    }

    /**
     * 打开/新建 cache 根目录。
     *
     * @param root 宿主数据目录下的专用子目录
     */
    public static SqliteSecretaryCacheStore open(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            Files.createDirectories(root);
            setPermissions(root, "rwx------");
            Path dbFile = root.resolve(DB_FILE);
            SqliteConversationStore store = SqliteConversationStore.open(dbFile);
            setPermissions(dbFile, "rw-------");
            return new SqliteSecretaryCacheStore(root, dbFile, store);
        } catch (IOException failure) {
            throw new IllegalStateException("打开秘书 cache 目录失败", failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("打开秘书 cache 存储失败", failure);
        }
    }

    @Override
    public CacheSummary ensureDefaultCache(String playerId) {
        String player = requirePlayerId(playerId);
        synchronized (lock) {
            CacheIndex resolved = readIndex(player);
            boolean hasActive = false;
            for (CacheEntry entry : resolved.caches) {
                if (entry.cacheId.equals(resolved.activeCacheId)) {
                    hasActive = true;
                    break;
                }
            }
            if (!hasActive) {
                if (resolved.caches.isEmpty()) {
                    CacheEntry created = newEntry("默认对话", "APP");
                    resolved = new CacheIndex(INDEX_VERSION, player, created.cacheId,
                            append(resolved.caches, created));
                } else {
                    resolved = new CacheIndex(INDEX_VERSION, player,
                            resolved.caches.getFirst().cacheId, resolved.caches);
                }
                writeIndex(player, resolved);
            }
            final CacheIndex index = resolved;
            return toSummary(player, index, index.caches.stream()
                    .filter(entry -> entry.cacheId.equals(index.activeCacheId))
                    .findFirst()
                    .orElseThrow());
        }
    }

    @Override
    public CacheSummary createCache(String playerId, String title, String source) {
        String player = requirePlayerId(playerId);
        String normalizedSource = normalizeSource(source);
        synchronized (lock) {
            CacheIndex index = readIndex(player);
            CacheEntry created = newEntry(title, normalizedSource);
            String active = index.activeCacheId == null || index.activeCacheId.isBlank()
                    ? created.cacheId : index.activeCacheId;
            CacheIndex updated = new CacheIndex(INDEX_VERSION, player, active,
                    append(index.caches, created));
            writeIndex(player, updated);
            return toSummary(player, updated, created);
        }
    }

    @Override
    public List<CacheSummary> listCaches(String playerId) {
        String player = requirePlayerId(playerId);
        synchronized (lock) {
            CacheIndex index = readIndex(player);
            return index.caches.stream()
                    .sorted(Comparator.comparing(entry -> entry.createdAt))
                    .map(entry -> toSummary(player, index, entry))
                    .toList();
        }
    }

    @Override
    public Optional<CacheSummary> findCache(String playerId, String cacheId) {
        String player = requirePlayerId(playerId);
        String id = requireCacheId(cacheId);
        synchronized (lock) {
            CacheIndex index = readIndex(player);
            return index.caches.stream()
                    .filter(entry -> entry.cacheId.equals(id))
                    .findFirst()
                    .map(entry -> toSummary(player, index, entry));
        }
    }

    @Override
    public String activeCacheId(String playerId) {
        String player = requirePlayerId(playerId);
        synchronized (lock) {
            return readIndex(player).activeCacheId;
        }
    }

    @Override
    public void setActiveCache(String playerId, String cacheId) {
        String player = requirePlayerId(playerId);
        String id = requireCacheId(cacheId);
        synchronized (lock) {
            CacheIndex index = readIndex(player);
            if (index.caches.stream().noneMatch(entry -> entry.cacheId.equals(id))) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_NOT_FOUND,
                        "cache 不存在");
            }
            writeIndex(player, new CacheIndex(INDEX_VERSION, player, id, touch(index.caches, id)));
        }
    }

    @Override
    public List<LlmMessage> loadMessages(String playerId, String cacheId) {
        String player = requirePlayerId(playerId);
        String id = requireCacheId(cacheId);
        synchronized (lock) {
            CacheIndex index = readIndex(player);
            if (index.caches.stream().noneMatch(entry -> entry.cacheId.equals(id))) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_NOT_FOUND,
                        "cache 不存在");
            }
            try {
                return List.copyOf(conversations.load(conversationKey(player, id)));
            } catch (RuntimeException failure) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                        "读取 cache 消息失败", failure);
            }
        }
    }

    @Override
    public void appendMessages(String playerId, String cacheId, List<LlmMessage> messages) {
        String player = requirePlayerId(playerId);
        String id = requireCacheId(cacheId);
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return;
        }
        synchronized (lock) {
            CacheIndex index = readIndex(player);
            CacheEntry target = index.caches.stream()
                    .filter(entry -> entry.cacheId.equals(id))
                    .findFirst()
                    .orElseThrow(() -> new SecretaryCacheException(
                            SecretaryCacheException.CACHE_NOT_FOUND, "cache 不存在"));
            try {
                for (LlmMessage message : messages) {
                    conversations.append(conversationKey(player, id), message);
                }
            } catch (RuntimeException failure) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_WRITE_FAILED,
                        "写入 cache 消息失败", failure);
            }
            int updatedCount = target.messageCount + messages.size();
            List<CacheEntry> updated = index.caches.stream()
                    .map(entry -> entry.cacheId.equals(id)
                            ? new CacheEntry(entry.cacheId, entry.title, entry.source,
                            entry.createdAt, Instant.now().toString(), updatedCount)
                            : entry)
                    .toList();
            writeIndex(player, new CacheIndex(INDEX_VERSION, player,
                    index.activeCacheId, updated));
        }
    }

    /** 诊断用数据库路径；不进入模型/MCP 结果。 */
    public Path dbFile() {
        return dbFile;
    }

    @Override
    public void close() {
        if (conversations instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("关闭秘书 cache 存储失败", failure);
            }
        }
    }

    private CacheEntry newEntry(String title, String source) {
        String safeTitle = title == null || title.isBlank() ? "未命名对话" : title.trim();
        if (safeTitle.length() > 200) {
            safeTitle = safeTitle.substring(0, 200);
        }
        String now = Instant.now().toString();
        return new CacheEntry("cache-" + UUID.randomUUID(), safeTitle, source,
                now, now, 0);
    }

    private CacheIndex readIndex(String playerId) {
        Path file = indexFile(playerId);
        if (!Files.exists(file)) {
            return new CacheIndex(INDEX_VERSION, playerId, null, List.of());
        }
        if (Files.isSymbolicLink(file)) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "cache 索引不得是软链");
        }
        try {
            CacheIndex index = mapper.readValue(Files.readString(file), CacheIndex.class);
            if (index == null || index.version != INDEX_VERSION
                    || !playerId.equals(index.playerId) || index.caches == null) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                        "cache 索引格式不兼容");
            }
            return new CacheIndex(INDEX_VERSION, playerId, index.activeCacheId,
                    List.copyOf(index.caches));
        } catch (SecretaryCacheException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_UNAVAILABLE,
                    "读取 cache 索引失败", failure);
        }
    }

    private void writeIndex(String playerId, CacheIndex index) {
        Path file = indexFile(playerId);
        Path dir = file.getParent();
        try {
            Files.createDirectories(dir);
            setPermissions(dir, "rwx------");
            Path tmp = Files.createTempFile(dir, ".index-", ".tmp");
            setPermissions(tmp, "rw-------");
            Files.writeString(tmp, mapper.writeValueAsString(index), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(file, "rw-------");
        } catch (IOException failure) {
            throw new SecretaryCacheException(SecretaryCacheException.CACHE_WRITE_FAILED,
                    "写入 cache 索引失败", failure);
        }
    }

    private Path indexFile(String playerId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(playerId.getBytes(StandardCharsets.UTF_8));
        return root.resolve(encoded).resolve(INDEX_FILE);
    }

    private static String conversationKey(String playerId, String cacheId) {
        return "secretary-cache:" + playerId + ":" + cacheId;
    }

    private static CacheSummary toSummary(String playerId, CacheIndex index, CacheEntry entry) {
        return new CacheSummary(entry.cacheId, playerId, entry.title, entry.source,
                entry.createdAt, entry.lastUsedAt, entry.messageCount,
                entry.cacheId.equals(index.activeCacheId));
    }

    private static String requirePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    "playerId 不能为空白");
        }
        return playerId.trim();
    }

    private static String requireCacheId(String cacheId) {
        if (cacheId == null || cacheId.isBlank() || !CACHE_ID.matcher(cacheId.trim()).matches()) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    "cacheId 格式非法");
        }
        return cacheId.trim();
    }

    private static String normalizeSource(String source) {
        String value = source == null || source.isBlank() ? "APP" : source.trim();
        if (!"APP".equals(value) && !"NAPCAT_IMPORT".equals(value)) {
            throw new SecretaryCacheException(SecretaryCacheException.INVALID_ARGUMENTS,
                    "source 只允许 APP 或 NAPCAT_IMPORT");
        }
        return value;
    }

    private static List<CacheEntry> append(List<CacheEntry> entries, CacheEntry created) {
        List<CacheEntry> copy = new ArrayList<>(entries);
        copy.add(created);
        return List.copyOf(copy);
    }

    private static List<CacheEntry> touch(List<CacheEntry> entries, String cacheId) {
        String now = Instant.now().toString();
        return entries.stream()
                .map(entry -> entry.cacheId.equals(cacheId)
                        ? new CacheEntry(entry.cacheId, entry.title, entry.source,
                        entry.createdAt, now, entry.messageCount)
                        : entry)
                .toList();
    }

    private static void setPermissions(Path path, String posix) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posix));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统或权限收紧失败不改变 cache 语义；日志层不记录路径内容。
        }
    }

    private record CacheIndex(int version, String playerId, String activeCacheId,
                              List<CacheEntry> caches) {
    }

    private record CacheEntry(String cacheId, String title, String source,
                              String createdAt, String lastUsedAt, int messageCount) {
    }
}
