package io.mosire.iibot.app.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DeliveryLedger} 的严格 JSON 文件实现。
 *
 * <p>默认文件为 {@code dataDir/delivery-ledger.json}，也支持 {@link #atFile(Path)} 注入。
 * 文件只保存 schema 版本和白名单投递 key，不保存消息正文、token、QQ 号或任何模型内容。</p>
 *
 * <p>读取使用严格 Jackson：未知字段、尾随 token、损坏 JSON、非法 key 或未知 schema 都
 * fail-closed。保存时先在内存中构造完整新集合，再写同目录临时文件并 flush/fsync，最后以
 * ATOMIC_MOVE + REPLACE_EXISTING 原子替换；只有保存成功后才替换实例内存状态。</p>
 *
 * <p>同一 JVM 内对同一路径的声明通过路径级锁串行化，并在每次声明前重新读取磁盘快照，
 * 因此多个 ledger 实例也不会把同一 key 同时声明成功。</p>
 */
public final class JsonDeliveryLedger implements DeliveryLedger {

    public static final String FILE_NAME = "delivery-ledger.json";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = createMapper();
    private static final ConcurrentHashMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();

    private final Path path;
    private final Object pathLock;
    private volatile Set<String> claimedKeys;

    /** 使用 {@code dataDir/delivery-ledger.json}。 */
    public JsonDeliveryLedger(Path dataDir) {
        this(Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize()
                .resolve(FILE_NAME), false);
    }

    /** 显式指定 ledger 文件路径。 */
    public static JsonDeliveryLedger atFile(Path ledgerPath) {
        return new JsonDeliveryLedger(ledgerPath, true);
    }

    private JsonDeliveryLedger(Path ledgerPath, boolean explicitPath) {
        Objects.requireNonNull(ledgerPath, "ledgerPath");
        Path absolute = ledgerPath.toAbsolutePath().normalize();
        if (absolute.getParent() == null) {
            throw new IllegalArgumentException("delivery-ledger 路径必须有父目录");
        }
        this.path = absolute;
        this.pathLock = PATH_LOCKS.computeIfAbsent(path, ignored -> new Object());
        this.claimedKeys = readKeys();
    }

    /** ledger 文件路径（诊断用，不含 ledger 内容）。 */
    public Path path() {
        return path;
    }

    /**
     * 读取磁盘上的投递 key 快照。
     *
     * @return 文件缺失时为空集合；损坏、IO 或未知 schema 时抛出具名异常
     */
    public Set<String> load() {
        return readKeys();
    }

    @Override
    public boolean claim(String key) {
        if (key == null || key.isBlank()) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.INVALID_KEY,
                    "投递 key 不能为空 path=" + path);
        }
        synchronized (pathLock) {
            // 以磁盘为唯一真源，兼顾同 JVM 内多个 ledger 实例（例如重启模拟/测试装配）。
            Set<String> current = new LinkedHashSet<>(readKeys());
            if (!current.add(key)) {
                claimedKeys = immutableOrdered(current);
                return false;
            }
            List<String> ordered = current.stream().sorted().toList();
            save(ordered);
            // 只有完整文件已经原子替换成功后，才让本实例看到新的内存集合。
            claimedKeys = immutableOrdered(ordered);
            return true;
        }
    }

    private Set<String> readKeys() {
        if (!Files.exists(path)) {
            return Set.of();
        }
        if (!Files.isRegularFile(path)) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.IO,
                    "delivery-ledger 路径不是普通文件 path=" + path);
        }
        try {
            LedgerFile file = MAPPER.readValue(path.toFile(), LedgerFile.class);
            if (file == null) {
                throw new DeliveryLedgerException(DeliveryLedgerException.Code.CORRUPT,
                        "delivery-ledger 解析结果为空 path=" + path);
            }
            if (file.schemaVersion() != CURRENT_SCHEMA_VERSION) {
                throw new DeliveryLedgerException(DeliveryLedgerException.Code.SCHEMA,
                        "不支持的 delivery-ledger schemaVersion=" + file.schemaVersion()
                                + " expected=" + CURRENT_SCHEMA_VERSION + " path=" + path);
            }
            List<String> keys = file.keys();
            if (keys == null) {
                throw new DeliveryLedgerException(DeliveryLedgerException.Code.CORRUPT,
                        "delivery-ledger keys 为空 path=" + path);
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    throw new DeliveryLedgerException(DeliveryLedgerException.Code.CORRUPT,
                            "delivery-ledger 包含非法 key path=" + path);
                }
                if (!unique.add(key)) {
                    throw new DeliveryLedgerException(DeliveryLedgerException.Code.CORRUPT,
                            "delivery-ledger 包含重复 key path=" + path);
                }
            }
            return immutableOrdered(unique);
        } catch (DeliveryLedgerException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.CORRUPT,
                    "delivery-ledger JSON 无法解析 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IOException failure) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.IO,
                    "读取 delivery-ledger 失败 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (RuntimeException failure) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.CORRUPT,
                    "delivery-ledger 内容无效 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        }
    }

    private void save(List<String> keys) {
        Path parent = path.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            byte[] json = MAPPER.writeValueAsBytes(
                    new LedgerFile(CURRENT_SCHEMA_VERSION, keys));
            temporary = Files.createTempFile(parent, "." + FILE_NAME + ".", ".tmp");
            hardenPermissions(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveIntoPlace(temporary);
            temporary = null;
            fsyncDirectory(parent);
        } catch (JsonProcessingException failure) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.SERIALIZE,
                    "delivery-ledger 序列化失败 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IOException failure) {
            throw new DeliveryLedgerException(DeliveryLedgerException.Code.SAVE,
                    "保存 delivery-ledger 失败 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 保留原始保存异常；正式文件因原子替换保证不会半写。
                }
            }
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Set<String> immutableOrdered(Iterable<String> keys) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        keys.forEach(copy::add);
        return Collections.unmodifiableSet(copy);
    }

    private static void hardenPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统允许失败；原子替换仍是主安全保证。
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 目录 fsync 并非所有平台支持；文件 fsync 与原子替换已完成。
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }

    /** 外层 schema 包装；只持久化投递 key，未知字段严格失败。 */
    public record LedgerFile(int schemaVersion, List<String> keys) {
        public LedgerFile {
            if (keys == null) {
                throw new IllegalArgumentException("keys 不能为空");
            }
            keys = List.copyOf(keys);
        }
    }
}
