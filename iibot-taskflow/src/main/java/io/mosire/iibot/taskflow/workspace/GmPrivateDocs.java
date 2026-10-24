package io.mosire.iibot.taskflow.workspace;

import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * GM 私有文档区：{@code dataDir/gm-private/{world,plot,settings,notes}}。
 *
 * <p>该区域只通过 GM 控制面工具访问，不注册到秘书/GM aide/玩家工具面。文件写入使用
 * 同目录临时文件、fsync 与原子 move；路径逐级拒绝软链和越界。事件 journal 是写入成功后的
 * best-effort 审计，失败不回滚文件。</p>
 */
public final class GmPrivateDocs {

    private static final Logger LOG = LoggerFactory.getLogger(GmPrivateDocs.class);

    /** MCP 工具声明的资源命名空间。 */
    public static final String RESOURCE_NAMESPACE = "gm-private";

    /** 唯一允许的四个逻辑根。 */
    public static final List<String> ROOT_DIRECTORIES = List.of(
            "world", "plot", "settings", "notes");

    /** 单篇 GM 私有文档最大 UTF-8 字节数。 */
    public static final int MAX_CONTENT_BYTES = 256 * 1024;

    /** 兼容按字符计数的调用方；落盘上限以 UTF-8 字节为准。 */
    public static final int MAX_CONTENT_CHARS = MAX_CONTENT_BYTES;

    private static final String BASE_DIRECTORY = "gm-private";
    private static final String EVENT_ACTOR_ID = "external-supervisor";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;
    private final DomainEventJournal journal;

    private GmPrivateDocs(Path root, DomainEventJournal journal) {
        this.root = root;
        this.journal = journal;
    }

    /** 建立/打开 GM 私有文档区，不写事件。 */
    public static GmPrivateDocs open(Path dataDir) {
        return open(dataDir, null);
    }

    /**
     * 建立/打开 GM 私有文档区；journal 非空时写入成功后的审计事件。
     */
    public static GmPrivateDocs open(Path dataDir, DomainEventJournal journal) {
        Objects.requireNonNull(dataDir, "dataDir");
        Path base = prepareDataDirectory(dataDir);
        Path root = ensureDirectory(base, base.resolve(BASE_DIRECTORY));
        for (String allowedRoot : ROOT_DIRECTORIES) {
            ensureDirectory(base, root.resolve(allowedRoot));
        }
        setDirectoryPermissions(root);
        return new GmPrivateDocs(root, journal);
    }

    /**
     * 列出逻辑路径、字节大小与更新时间；不返回物理路径。
     *
     * @param prefix 空或四个逻辑根之一；列该根下全部普通文件（递归，不跟随软链）
     */
    public List<Entry> list(String prefix) {
        String relative = normalizePrefix(prefix);
        ensureSafeRoot();
        if (relative.isEmpty()) {
            List<Entry> all = new ArrayList<>();
            for (String allowedRoot : ROOT_DIRECTORIES) {
                all.addAll(listRoot(allowedRoot));
            }
            all.sort(Comparator.comparing(Entry::path));
            return List.copyOf(all);
        }
        return listRoot(relative);
    }

    /** 读取文件正文、SHA-256 与字节大小；不存在返回 empty。 */
    public Optional<ReadResult> read(String path) {
        String relative = requireFilePath(path, "读取");
        ensureSafeRoot();
        Path file = resolveExistingFile(relative);
        if (file == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String content = decodeUtf8(bytes);
            return Optional.of(new ReadResult(relative, content,
                    Hashes.sha256(content), bytes.length));
        } catch (CharacterCodingException invalidUtf8) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档不是合法 UTF-8 文本");
        } catch (IOException failure) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档读取失败（fail-closed）");
        }
    }

    /**
     * 写文件。默认 {@code replace=false} 拒绝覆盖；成功后才 best-effort 写审计事件。
     */
    public synchronized WriteResult write(String path, String content, boolean replace) {
        String relative = requireFilePath(path, "写入");
        byte[] bytes = encodeUtf8(content);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档超过 256 KiB 上限");
        }
        ensureSafeRoot();
        Path target = resolveWriteTarget(relative);
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (existed && !replace) {
            throw failure(GmPrivateDocsException.CONFLICT,
                    "GM 私有文档已存在（默认拒绝覆盖）");
        }
        if (existed && (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档目标不是普通文件");
        }

        Path temp = null;
        try {
            temp = createTempFile(target.getParent());
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            setFilePermissions(temp);
            if (replace) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            temp = null;
            setFilePermissions(target);
            String hash = Hashes.sha256(content == null ? "" : content);
            String status = existed ? "replaced" : "created";
            appendAuditBestEffort(relative, content == null ? "" : content,
                    hash, bytes.length, replace, status);
            return new WriteResult(relative, status, hash, bytes.length);
        } catch (FileAlreadyExistsException conflict) {
            throw failure(GmPrivateDocsException.CONFLICT,
                    "GM 私有文档已存在（默认拒绝覆盖）");
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档原子写入不受支持（fail-closed）");
        } catch (IOException ioFailure) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档写入失败（fail-closed）");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不能覆盖原失败；不输出路径。
                }
            }
        }
    }

    /** 物理根，仅供宿主诊断/测试；不进入 MCP 工具结果。 */
    public Path rootForDiagnostics() {
        return root;
    }

    private List<Entry> listRoot(String relativeRoot) {
        Path directory = root.resolve(relativeRoot);
        validateExistingDirectory(directory);
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                collectFiles(child, entries);
            }
        } catch (IOException failure) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档目录不可列出（fail-closed）");
        }
        entries.sort(Comparator.comparing(Entry::path));
        return List.copyOf(entries);
    }

    private void collectFiles(Path candidate, List<Entry> output) throws IOException {
        if (Files.isSymbolicLink(candidate)) {
            return;
        }
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档目录真实路径越界（fail-closed）");
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(candidate)) {
                for (Path child : children) {
                    collectFiles(child, output);
                }
            }
            return;
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档真实路径越界（fail-closed）");
        }
        Path relative = root.relativize(candidate.toAbsolutePath().normalize());
        String logicalPath = relative.toString().replace('\\', '/');
        FileTime updated = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS);
        output.add(new Entry(logicalPath, Files.size(candidate), updated.toInstant()));
    }

    private Path resolveExistingFile(String relative) {
        String[] segments = relative.split("/", -1);
        Path current = root;
        for (int index = 0; index < segments.length; index++) {
            current = current.resolve(segments[index]);
            boolean last = index == segments.length - 1;
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (Files.isSymbolicLink(current)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档路径含软链（fail-closed）");
            }
            if (last) {
                if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                            "GM 私有文档目标不是普通文件");
                }
            } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档路径父级不是目录");
            }
            try {
                Path real = current.toRealPath();
                if (!real.startsWith(root)) {
                    throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                            "GM 私有文档真实路径越界（fail-closed）");
                }
            } catch (IOException failure) {
                throw failure(GmPrivateDocsException.UNAVAILABLE,
                        "GM 私有文档路径不可解析（fail-closed）");
            }
        }
        return current;
    }

    private Path resolveWriteTarget(String relative) {
        String[] segments = relative.split("/", -1);
        Path current = root;
        for (int index = 0; index < segments.length; index++) {
            current = current.resolve(segments[index]);
            boolean last = index == segments.length - 1;
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                            "GM 私有文档路径含软链（fail-closed）");
                }
                if (!last && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                            "GM 私有文档路径父级不是目录");
                }
                try {
                    Path real = current.toRealPath();
                    if (!real.startsWith(root)) {
                        throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                                "GM 私有文档真实路径越界（fail-closed）");
                    }
                } catch (IOException failure) {
                    throw failure(GmPrivateDocsException.UNAVAILABLE,
                            "GM 私有文档路径不可解析（fail-closed）");
                }
                continue;
            }
            if (last) {
                continue;
            }
            try {
                Files.createDirectory(current);
                setDirectoryPermissions(current);
            } catch (FileAlreadyExistsException race) {
                validateExistingDirectory(current);
            } catch (IOException failure) {
                throw failure(GmPrivateDocsException.UNAVAILABLE,
                        "GM 私有文档目录不可创建（fail-closed）");
            }
        }
        return current;
    }

    private Path createTempFile(Path parent) throws IOException {
        try {
            return Files.createTempFile(parent, ".gm-doc-", ".tmp",
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            Path temp = Files.createTempFile(parent, ".gm-doc-", ".tmp");
            setFilePermissions(temp);
            return temp;
        }
    }

    private void ensureSafeRoot() {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档根不可用（fail-closed）");
        }
        try {
            if (!root.toRealPath().equals(root)) {
                throw failure(GmPrivateDocsException.UNAVAILABLE,
                        "GM 私有文档根真实路径不一致（fail-closed）");
            }
        } catch (IOException failure) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档根不可用（fail-closed）");
        }
    }

    private void validateExistingDirectory(Path directory) {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档路径不是安全目录");
        }
        try {
            if (!directory.toRealPath().startsWith(root)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档目录真实路径越界（fail-closed）");
            }
        } catch (IOException failure) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档目录不可用（fail-closed）");
        }
    }

    private void appendAuditBestEffort(String path, String content, String contentHash,
                                       long size, boolean replace, String status) {
        if (journal == null) {
            return;
        }
        try {
            Map<String, Object> eventContent = new LinkedHashMap<>();
            eventContent.put("path", path);
            eventContent.put("content", content);
            eventContent.put("contentHash", contentHash);
            eventContent.put("size", size);
            eventContent.put("replace", replace);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("path", path);
            summary.put("contentHash", contentHash);
            summary.put("size", size);
            summary.put("status", status);
            String eventType = "created".equals(status)
                    ? DomainEventTypes.GM_DOC_CREATED : DomainEventTypes.GM_DOC_UPDATED;
            journal.append(new DomainEvent(Instant.now(), eventType,
                    ActorRole.SUPERVISOR, EVENT_ACTOR_ID,
                    "gm-doc:" + status, null, EventVisibility.GM,
                    eventContent, summary));
        } catch (RuntimeException failure) {
            // 文件已成功落盘；审计失败只记录稳定类型，绝不回滚或伪装成写失败。
            LOG.error("GM 私有文档审计事件追加失败 type={} errorType={}",
                    "created".equals(status) ? DomainEventTypes.GM_DOC_CREATED
                            : DomainEventTypes.GM_DOC_UPDATED,
                    failure.getClass().getSimpleName());
        }
    }

    private static Path prepareDataDirectory(Path dataDir) {
        Path requested = dataDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(requested);
            setDirectoryPermissions(requested);
            return requested.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("无法建立 GM 私有文档数据目录");
        }
    }

    private static Path ensureDirectory(Path base, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档目录越界（fail-closed）");
        }
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized)
                        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                            "GM 私有文档目录不是安全目录（fail-closed）");
                }
                if (!normalized.toRealPath().startsWith(base)) {
                    throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                            "GM 私有文档目录真实路径越界（fail-closed）");
                }
                setDirectoryPermissions(normalized);
                return normalized;
            }
            Path parent = normalized.getParent();
            if (parent == null) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档目录缺少父目录");
            }
            ensureDirectory(base, parent);
            Files.createDirectory(normalized);
            if (Files.isSymbolicLink(normalized)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档目录出现软链（fail-closed）");
            }
            setDirectoryPermissions(normalized);
            return normalized;
        } catch (IOException failure) {
            throw failure(GmPrivateDocsException.UNAVAILABLE,
                    "GM 私有文档目录不可建立（fail-closed）");
        }
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String relative = normalizePath(raw);
        if (relative.contains("/")) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "list 前缀只允许 world/plot/settings/notes 或空");
        }
        return relative;
    }

    private static String requireFilePath(String raw, String action) {
        String relative = normalizePath(raw);
        if (relative.split("/", -1).length < 2) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档" + action + "路径必须指向逻辑根下的文件");
        }
        return relative;
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档路径不能为空");
        }
        if (raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0
                || raw.startsWith("/") || raw.startsWith("~")
                || raw.matches("^[A-Za-z]:.*")
                || raw.chars().anyMatch(Character::isISOControl)) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档路径必须是白名单内的相对路径");
        }
        String[] segments = raw.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档路径不得包含空段、. 或 ..");
            }
        }
        if (!ROOT_DIRECTORIES.contains(segments[0])) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档路径必须位于 world/plot/settings/notes");
        }
        return String.join("/", segments);
    }

    private static byte[] encodeUtf8(String content) {
        if (content == null) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档内容不能为 null");
        }
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            // 正文允许 Unix/Windows 换行与制表符，但 NUL 及其他 ISO control 一律拒绝。
            if (character == '\n' || character == '\r' || character == '\t') {
                continue;
            }
            if (Character.isISOControl(character)) {
                throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                        "GM 私有文档内容含非法控制字符");
            }
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(content));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw failure(GmPrivateDocsException.INVALID_ARGUMENTS,
                    "GM 私有文档内容不是合法 UTF-8 文本");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static void setDirectoryPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统无法设置权限；安全边界仍由路径/软链检查保证。
        }
    }

    private static void setFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 同上，best-effort。
        }
    }

    private static GmPrivateDocsException failure(String code, String message) {
        return new GmPrivateDocsException(code, message);
    }

    /** 列表项：逻辑路径、字节大小、更新时间。 */
    public record Entry(String path, long size, Instant updatedAt) {
        public Entry {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path 不能为空");
            }
            if (size < 0) {
                throw new IllegalArgumentException("size 不能为负");
            }
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        /** 兼容“最后修改时间”命名。 */
        public Instant lastModified() {
            return updatedAt;
        }
    }

    /** 读取结果：正文、SHA-256、字节大小。 */
    public record ReadResult(String path, String content, String contentHash, long size) {
        public ReadResult {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path 不能为空");
            }
            Objects.requireNonNull(content, "content");
            if (contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException("contentHash 不能为空");
            }
            if (size < 0) {
                throw new IllegalArgumentException("size 不能为负");
            }
        }

        public String hash() {
            return contentHash;
        }
    }

    /** 写入结果：逻辑路径、created/replaced、SHA-256、字节大小。 */
    public record WriteResult(String path, String status, String contentHash, long size) {
        public WriteResult {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path 不能为空");
            }
            if (!"created".equals(status) && !"replaced".equals(status)) {
                throw new IllegalArgumentException("status 必须是 created/replaced");
            }
            if (contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException("contentHash 不能为空");
            }
            if (size < 0) {
                throw new IllegalArgumentException("size 不能为负");
            }
        }

        public boolean created() {
            return "created".equals(status);
        }

        public boolean replaced() {
            return "replaced".equals(status);
        }

        public String hash() {
            return contentHash;
        }
    }
}
