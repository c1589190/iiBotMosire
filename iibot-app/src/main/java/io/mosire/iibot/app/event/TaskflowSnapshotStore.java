package io.mosire.iibot.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * TaskflowEngine 完整状态快照的原子 JSON 存储。
 *
 * <p>存储只包含领域快照本身，不包含 EventSink、LLM、QQ 连接或密钥。读取使用严格的
 * Jackson 配置：未知字段和根节点后的尾随 token 都会失败，schemaVersion 必须等于当前
 * 支持版本。写入先完成同目录临时文件的 flush/fsync，再以 ATOMIC_MOVE + REPLACE_EXISTING
 * 替换正式文件；文件系统不支持原子移动时退化为普通 replace，但正式文件永远不会被半写。</p>
 *
 * <p>异常消息只包含路径/序号/异常类别等诊断信息，不输出文件正文或领域内容。</p>
 */
public final class TaskflowSnapshotStore {

    public static final String FILE_NAME = "taskflow-state.json";

    private static final ObjectMapper MAPPER = createMapper();

    private final Path path;

    /** 使用 {@code dataDir/taskflow-state.json}。 */
    public TaskflowSnapshotStore(Path dataDir) {
        this(Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize()
                .resolve(FILE_NAME), false);
    }

    /** 显式指定快照文件路径。 */
    public static TaskflowSnapshotStore atFile(Path snapshotPath) {
        return new TaskflowSnapshotStore(snapshotPath, true);
    }

    private TaskflowSnapshotStore(Path snapshotPath, boolean explicitPath) {
        this.path = normalize(snapshotPath);
    }

    private static Path normalize(Path snapshotPath) {
        Objects.requireNonNull(snapshotPath, "snapshotPath");
        Path absolute = snapshotPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("快照路径必须有父目录");
        }
        return absolute;
    }

    /** 快照文件路径（诊断用，不含快照内容）。 */
    public Path path() {
        return path;
    }

    /** @return 文件不存在时为空；损坏、IO 或未知 schema 时抛出具名异常。 */
    public Optional<TaskflowStateSnapshot> load() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_IO] 快照路径不是普通文件 path="
                    + path);
        }
        try {
            TaskflowStateSnapshot snapshot = MAPPER.readValue(path.toFile(),
                    TaskflowStateSnapshot.class);
            if (snapshot == null) {
                throw new IllegalStateException("[TASKFLOW_SNAPSHOT_CORRUPT] 快照解析结果为空 path="
                        + path);
            }
            if (snapshot.schemaVersion() != TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException("[TASKFLOW_SNAPSHOT_SCHEMA] 不支持的快照 schemaVersion="
                        + snapshot.schemaVersion() + " expected="
                        + TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION + " path=" + path);
            }
            return Optional.of(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_CORRUPT] 快照 JSON 无法解析 path="
                    + path + " error=" + e.getClass().getSimpleName());
        } catch (IOException e) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_IO] 读取快照失败 path=" + path
                    + " error=" + e.getClass().getSimpleName());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_CORRUPT] 快照内容无效 path="
                    + path + " error=" + e.getClass().getSimpleName());
        }
    }

    /**
     * 原子保存完整快照。失败时抛出异常，不静默忽略，也不把部分 JSON 暴露为正式文件。
     */
    public void save(TaskflowStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path parent = path.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            byte[] json = MAPPER.writeValueAsBytes(snapshot);
            temporary = Files.createTempFile(parent, "." + FILE_NAME + ".", ".tmp");
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_SERIALIZE_FAILED] 快照序列化失败 path="
                    + path + " error=" + e.getClass().getSimpleName());
        } catch (IOException e) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_SAVE_FAILED] 保存快照失败 path="
                    + path + " error=" + e.getClass().getSimpleName());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 保留原始保存异常；临时文件失败清理不是正式快照状态的一部分。
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

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}
