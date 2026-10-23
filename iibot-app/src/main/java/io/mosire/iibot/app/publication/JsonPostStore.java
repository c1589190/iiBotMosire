package io.mosire.iibot.app.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.post.PostStore;

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
import java.util.Objects;
import java.util.Set;

/**
 * 推文快照的严格 JSON 持久化实现。
 *
 * <p>文件固定在 {@code dataDir/posts.json}（也支持显式注入文件路径）。快照没有自身
 * schemaVersion，因此这里用外层 {@link PostSnapshotFile} 包装，不修改 taskflow 契约。</p>
 *
 * <p><b>读取 fail-closed</b>：文件缺失返回空快照；损坏 JSON、未知字段、尾随 token、非法
 * schema 或领域校验失败都抛 {@link PostStoreException}，绝不回退为空而覆盖旧数据。</p>
 *
 * <p><b>写入原子替换</b>：先写同目录临时文件并 flush/fsync，再以 ATOMIC_MOVE +
 * REPLACE_EXISTING 替换正式文件；文件系统不支持 ATOMIC_MOVE 时退化为完整文件 replace。
 * 临时文件权限尽量设为 600，失败清理且不会留下半写正式文件。日志/异常不包含正文。</p>
 */
public final class JsonPostStore implements PostStore {

    public static final String FILE_NAME = "posts.json";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = createMapper();

    private final Path path;

    /** 使用 {@code dataDir/posts.json}。 */
    public JsonPostStore(Path dataDir) {
        this(Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize()
                .resolve(FILE_NAME), false);
    }

    /** 显式指定快照文件路径。 */
    public static JsonPostStore atFile(Path snapshotPath) {
        return new JsonPostStore(snapshotPath, true);
    }

    private JsonPostStore(Path snapshotPath, boolean explicitPath) {
        Objects.requireNonNull(snapshotPath, "snapshotPath");
        Path absolute = snapshotPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("posts.json 路径必须有父目录");
        }
        this.path = absolute;
    }

    /** 快照文件路径（诊断用，不包含快照内容）。 */
    public Path path() {
        return path;
    }

    @Override
    public PostSnapshot load() {
        if (!Files.exists(path)) {
            return PostSnapshot.empty();
        }
        if (!Files.isRegularFile(path)) {
            throw new PostStoreException(PostStoreException.Code.IO,
                    "推文快照路径不是普通文件 path=" + path);
        }
        try {
            PostSnapshotFile file = MAPPER.readValue(path.toFile(), PostSnapshotFile.class);
            if (file == null || file.snapshot() == null) {
                throw new PostStoreException(PostStoreException.Code.CORRUPT,
                        "推文快照解析结果为空 path=" + path);
            }
            if (file.schemaVersion() != CURRENT_SCHEMA_VERSION) {
                throw new PostStoreException(PostStoreException.Code.SCHEMA,
                        "不支持的推文快照 schemaVersion=" + file.schemaVersion()
                                + " expected=" + CURRENT_SCHEMA_VERSION + " path=" + path);
            }
            return file.snapshot();
        } catch (JsonProcessingException failure) {
            throw new PostStoreException(PostStoreException.Code.CORRUPT,
                    "推文快照 JSON 无法解析 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IOException failure) {
            throw new PostStoreException(PostStoreException.Code.IO,
                    "读取推文快照失败 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IllegalArgumentException failure) {
            // Jackson/领域 record 校验不通过；只保留异常类型，避免正文进入消息。
            throw new PostStoreException(PostStoreException.Code.CORRUPT,
                    "推文快照内容无效 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        }
    }

    @Override
    public void save(PostSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path parent = path.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            byte[] json = MAPPER.writeValueAsBytes(
                    new PostSnapshotFile(CURRENT_SCHEMA_VERSION, snapshot));
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
            throw new PostStoreException(PostStoreException.Code.SERIALIZE,
                    "推文快照序列化失败 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } catch (IOException failure) {
            throw new PostStoreException(PostStoreException.Code.SAVE,
                    "保存推文快照失败 path=" + path
                            + " error=" + failure.getClass().getSimpleName(), failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 保留原始失败；临时文件清理失败不影响正式文件从未半写的保证。
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

    private static void hardenPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions =
                    PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统允许失败；原子写入仍是主安全保证。
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 目录 fsync 不是所有平台支持；文件内容 fsync 和原子移动已完成。
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

    /**
     * 外层 schema 包装；只持久化需要的字段，未知字段严格失败。
     */
    public record PostSnapshotFile(int schemaVersion, PostSnapshot snapshot) {
        public PostSnapshotFile {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
