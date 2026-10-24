package io.mosire.iibot.app.records;

import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.post.PostService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * records Markdown 投影的只读查询面。
 *
 * <p>只返回逻辑 ID、安全相对 file、Markdown 内容/摘要与 hash；不返回物理路径。
 * turn_get 只接受 index/public/players/actions/packages/batches/posts 下的单层安全
 * 相对路径，并拒绝绝对路径、{@code ..}、反斜杠、控制字符与软链。kind=opening
 * 摘要来自 records/openings，turnId 为 null，不伪装成回合。</p>
 */
public final class RecordQueryService {

    public static final Set<String> KINDS = Set.of(
            "turn", "public-narrative", "player", "action", "package", "batch", "post",
            "opening");
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final Pattern HASH_LINE =
            Pattern.compile("^- `([^`]+)`: `([0-9a-f]{64})`$");
    private static final List<String> MANAGED_DIRECTORIES =
            List.of("players", "actions", "packages", "batches", "posts");

    private final Path dataDir;
    private final TurnRecordProjector projector;

    /** projector 允许为 null：此时查询完全基于已验证的 Markdown index/manifest。 */
    public RecordQueryService(Path dataDir, TurnRecordProjector projector) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize();
        this.projector = projector;
    }

    public RecordQueryService(TurnRecordProjector projector, Path dataDir) {
        this(dataDir, projector);
    }

    /**
     * 文件系统-only 兼容构造。projector 显式为 null，不读取任何领域服务；list/query
     * 只扫描带完整可验证 index manifest 的已投影目录。records/turns 尚不存在时
     * listTurns 返回空列表，而不是 NPE/未处理 IO。
     */
    public RecordQueryService(Path dataDir) {
        this(dataDir, (TurnRecordProjector) null);
    }

    /**
     * 兼容以完整领域依赖构造的装配方式；内部建立同源 projector 元数据视图。
     * 调用方仍应显式执行 {@link TurnRecordProjector#rebuildAll()} 生成 Markdown。
     */
    public RecordQueryService(Path dataDir, TaskflowEngine engine,
                              ActionPackageService packageService,
                              PostService postService,
                              PublicationOrchestrator publications) {
        this(dataDir, new TurnRecordProjector(dataDir, engine, packageService,
                postService, publications));
    }

    /** 依赖顺序不同的兼容装配别名。 */
    public RecordQueryService(TaskflowEngine engine,
                              ActionPackageService packageService,
                              PostService postService,
                              PublicationOrchestrator publications,
                              Path dataDir) {
        this(dataDir, engine, packageService, postService, publications);
    }

    /** turn list 摘要；不含正文与物理路径。 */
    public synchronized List<TurnRecordSummary> listTurns() {
        // projector 非空时它是权威视图：只暴露最后一次成功 rebuild 的 index，
        // 尚未成功则返回空，绝不扫描可能半写/未索引的磁盘内容。只有显式 FS-only
        // 构造器（projector == null）才做可验证 index 扫描。
        List<TurnRecordSummary> result = projector == null
                ? scanTurnSummaries()
                : (projector.hasSuccessfulProjection() ? projector.summaries() : List.of());
        return result.stream()
                .sorted(Comparator.comparingInt(TurnRecordSummary::turnNo)
                        .thenComparing(TurnRecordSummary::turnId))
                .toList();
    }

    /** {@link #listTurns()} 的短别名。 */
    public List<TurnRecordSummary> turnList() {
        return listTurns();
    }

    public List<TurnRecordSummary> listTurnSummaries() {
        return listTurns();
    }

    /** 读取一个 turn 的允许 Markdown 文件，并校验 index manifest 与全部文件 hash。 */
    public synchronized TurnRecordFile getTurn(String turnId, String file) {
        String safeTurnId = TurnRecordProjector.safeLogicalId(turnId, "turnId");
        String normalizedFile = normalizeRelativeFile(file);
        Map<String, String> expectedManifest = null;
        if (projector != null) {
            if (!projector.hasSuccessfulProjection()) {
                throw inconsistent("尚无成功投影，拒绝读取未索引内容");
            }
            boolean projected = projector.summaries().stream()
                    .anyMatch(summary -> summary.turnId().equals(safeTurnId));
            if (!projected) {
                throw new RecordProjectionException(RecordProjectionException.FILE_NOT_FOUND,
                        "回合不在最后一次成功投影中: " + safeTurnId);
            }
            expectedManifest = projector.manifestForTurn(safeTurnId);
            if (expectedManifest.isEmpty()) {
                throw inconsistent("最后一次成功投影缺少 manifest");
            }
        }
        Path turnDir = resolveTurnDirectory(safeTurnId).orElseThrow(() ->
                new RecordProjectionException(RecordProjectionException.FILE_NOT_FOUND,
                        "回合记录不存在: " + safeTurnId));
        String indexContent = readText(turnDir.resolve("index.md"));
        Map<String, String> diskManifest = parseAndValidateIndex(turnDir, indexContent);
        if (expectedManifest != null) {
            String expectedIndexHash = expectedManifest.get("index.md");
            if (expectedIndexHash == null
                    || !expectedIndexHash.equals(Hashes.sha256(indexContent))) {
                throw inconsistent("当前 index.md 不属于最后一次成功投影");
            }
            Map<String, String> expectedWithoutIndex = new LinkedHashMap<>(expectedManifest);
            expectedWithoutIndex.remove("index.md");
            if (!expectedWithoutIndex.equals(diskManifest)) {
                throw inconsistent("当前 index manifest 与最后一次成功投影不一致");
            }
        }
        String expectedHash = normalizedFile.equals("index.md")
                ? (expectedManifest == null
                        ? Hashes.sha256(indexContent) : expectedManifest.get("index.md"))
                : diskManifest.get(normalizedFile);
        if (expectedHash == null) {
            throw inconsistent("请求文件未出现在 index manifest");
        }
        String content = normalizedFile.equals("index.md")
                ? indexContent : readText(turnDir.resolve(TurnRecordProjector.relativePath(normalizedFile)));
        if (!expectedHash.equals(Hashes.sha256(content))) {
            throw inconsistent("请求文件内容 hash 与 index manifest 不一致");
        }
        return new TurnRecordFile(safeTurnId, normalizedFile, content, expectedHash);
    }

    /** {@link #getTurn(String, String)} 的短别名。 */
    public TurnRecordFile turnGet(String turnId, String file) {
        return getTurn(turnId, file);
    }

    /**
     * 按 kind/turnId/playerId/postId/batchId 过滤记录摘要。null/空白参数表示不过滤。
     * 所有摘要均不含正文或物理路径。
     */
    public synchronized List<RecordSummary> query(String kind, String turnId,
                                                   String playerId, String postId,
                                                   String batchId) {
        String kindFilter = optional(kind);
        if (kindFilter != null) {
            kindFilter = switch (kindFilter.toLowerCase(Locale.ROOT)) {
                case "index" -> "turn";
                case "public", "publicnarrative" -> "public-narrative";
                case "players" -> "player";
                case "actions" -> "action";
                case "packages" -> "package";
                case "batches" -> "batch";
                case "posts" -> "post";
                case "openings" -> "opening";
                default -> kindFilter.toLowerCase(Locale.ROOT);
            };
            if (!KINDS.contains(kindFilter)) {
                throw new RecordProjectionException(RecordProjectionException.INVALID_ARGUMENTS,
                        "未知记录 kind");
            }
        }
        final String normalizedKind = kindFilter;
        final String turnFilter = optional(turnId);
        final String playerFilter = optional(playerId);
        final String postFilter = optional(postId);
        final String batchFilter = optional(batchId);
        if (turnFilter != null) {
            TurnRecordProjector.safeLogicalId(turnFilter, "turnId");
        }
        if (postFilter != null) {
            TurnRecordProjector.safeLogicalId(postFilter, "postId");
        }
        if (batchFilter != null) {
            TurnRecordProjector.safeLogicalId(batchFilter, "batchId");
        }
        if (playerFilter != null) {
            TurnRecordProjector.safeLogicalId(playerFilter, "playerId");
        }
        // projector 非空时只查询最后一次成功投影摘要；FS-only 构造器才扫描 index。
        List<RecordSummary> source;
        if (projector == null) {
            source = new ArrayList<>(scanRecordSummaries());
            source.addAll(scanOpeningRecordSummaries());
        } else if (projector.hasSuccessfulProjection()) {
            source = new ArrayList<>(projector.recordSummaries());
            source.addAll(projector.openingRecordSummaries());
        } else {
            source = List.of();
        }
        return source.stream()
                .filter(item -> normalizedKind == null || item.kind().equals(normalizedKind))
                .filter(item -> turnFilter == null || turnFilter.equals(item.turnId()))
                .filter(item -> playerFilter == null || playerFilter.equals(item.playerId()))
                .filter(item -> postFilter == null || postFilter.equals(item.postId()))
                .filter(item -> batchFilter == null || batchFilter.equals(item.batchId()))
                .toList();
    }

    /** 无过滤查询全部摘要。 */
    public List<RecordSummary> query() {
        return query(null, null, null, null, null);
    }

    public List<RecordSummary> queryRecords(String kind, String turnId,
                                             String playerId, String postId,
                                             String batchId) {
        return query(kind, turnId, playerId, postId, batchId);
    }

    private Optional<Path> resolveTurnDirectory(String turnId) {
        Path root;
        try {
            root = turnsRoot();
        } catch (RecordProjectionException failure) {
            throw failure;
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path matched = null;
        try (var stream = Files.list(root)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (Files.isSymbolicLink(child)
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                int dash = name.indexOf('-');
                if (dash <= 0 || !name.substring(dash + 1).equals(turnId)) {
                    continue;
                }
                String number = name.substring(0, dash);
                try {
                    if (Integer.parseInt(number) <= 0) {
                        continue;
                    }
                } catch (NumberFormatException invalid) {
                    continue;
                }
                if (matched != null) {
                    throw new RecordProjectionException(
                            RecordProjectionException.PROJECTION_FAILED,
                            "同一 turnId 存在多个记录目录");
                }
                matched = child;
            }
            if (matched == null) {
                return Optional.empty();
            }
            Path real = matched.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "回合记录目录逃逸 records/turns");
            }
            return Optional.of(real);
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "定位回合记录目录失败", failure);
        }
    }

    private static Path resolveAllowedFile(Path turnDir, String relativeFile) {
        Path candidate = turnDir.resolve(Path.of(relativeFile)).normalize();
        if (!candidate.startsWith(turnDir)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "记录文件路径逃逸回合目录");
        }
        Path current = turnDir;
        for (Path part : turnDir.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "记录文件路径不得经过软链");
            }
        }
        try {
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(turnDir)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "记录文件真实路径逃逸回合目录");
            }
            return real;
        } catch (IOException failure) {
            throw new RecordProjectionException(RecordProjectionException.FILE_NOT_FOUND,
                    "回合记录文件不存在", failure);
        }
    }

    private static String normalizeRelativeFile(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RecordProjectionException(RecordProjectionException.INVALID_ARGUMENTS,
                    "file 不能为空白");
        }
        String file = raw.trim();
        if (file.startsWith("/") || file.contains("\\") || file.contains("..")
                || file.chars().anyMatch(Character::isISOControl)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "file 必须是安全相对路径");
        }
        if (!file.contains("/") && !file.contains(".")) {
            file = switch (file) {
                case "index" -> "index.md";
                case "public", "public-narrative" -> "public-narrative.md";
                default -> file;
            };
        }
        String[] parts = file.split("/", -1);
        if (parts.length == 1) {
            if (!"index.md".equals(parts[0]) && !"public-narrative.md".equals(parts[0])) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "只允许 index.md 或 public-narrative.md");
            }
            return parts[0];
        }
        if (parts.length != 2 || !Set.of("players", "actions", "packages",
                "batches", "posts").contains(parts[0])) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "file 只允许 players/actions/packages/batches/posts 下的单层文件");
        }
        String name = parts[1];
        if (name.isBlank() || ".".equals(name) || "..".equals(name)
                || name.startsWith(".") || name.contains("/") || name.contains("\\")) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "file 文件名非法");
        }
        if (!name.endsWith(".md")) {
            name = name + ".md";
        }
        String stem = name.substring(0, name.length() - 3);
        TurnRecordProjector.safeLogicalId(stem, "file");
        return parts[0] + "/" + name;
    }

    private List<TurnRecordSummary> scanTurnSummaries() {
        Path root = turnsRoot();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<TurnRecordSummary> result = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path child : stream.toList()) {
                if (Files.isSymbolicLink(child)
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = child.getFileName().toString();
                int dash = name.indexOf('-');
                if (dash <= 0) {
                    continue;
                }
                int turnNo;
                try {
                    turnNo = Integer.parseInt(name.substring(0, dash));
                } catch (NumberFormatException invalid) {
                    continue;
                }
                if (turnNo <= 0) {
                    continue;
                }
                String turnId = name.substring(dash + 1);
                TurnRecordProjector.safeLogicalId(turnId, "turnId");
                Path index = child.resolve("index.md");
                if (Files.isSymbolicLink(index)
                        || !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String markdown;
                try {
                    markdown = readText(index);
                    parseAndValidateIndex(child, markdown);
                } catch (RecordProjectionException inconsistentIndex) {
                    // FS-only 视图只暴露可验证的完整投影；损坏/半写 index 不进入列表。
                    continue;
                }
                result.add(parseIndexSummary(turnNo, turnId, markdown));
            }
            return List.copyOf(result);
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "扫描回合记录失败", failure);
        }
    }

    private static TurnRecordSummary parseIndexSummary(int turnNo, String turnId,
                                                       String markdown) {
        String status = lineValue(markdown, "status").orElse("UNKNOWN");
        long worldVersion = parseLong(lineValue(markdown, "worldVersion").orElse("0"), 0L);
        Instant openedAt = parseInstant(lineValue(markdown, "opensAt").orElse(null));
        Instant closedAt = parseInstant(lineValue(markdown, "closesAt").orElse(null));
        return new TurnRecordSummary(turnNo, turnId, status, worldVersion,
                openedAt, closedAt,
                parseList(lineValue(markdown, "batchIds").orElse("")),
                parseList(lineValue(markdown, "packageIds").orElse("")),
                parseList(lineValue(markdown, "postIds").orElse("")),
                parseList(lineValue(markdown, "publicationIds").orElse("")));
    }

    private List<RecordSummary> scanRecordSummaries() {
        try {
            return scanRecordSummariesUnchecked();
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "扫描回合记录摘要失败", failure);
        }
    }

    private List<RecordSummary> scanRecordSummariesUnchecked() throws IOException {
        List<RecordSummary> result = new ArrayList<>();
        for (TurnRecordSummary turn : scanTurnSummaries()) {
            Optional<Path> dir = resolveTurnDirectory(turn.turnId());
            if (dir.isEmpty()) {
                continue;
            }
            Path turnDir = dir.orElseThrow();
            String indexContent = readText(turnDir.resolve("index.md"));
            Map<String, String> manifest;
            try {
                manifest = parseAndValidateIndex(turnDir, indexContent);
            } catch (RecordProjectionException inconsistentIndex) {
                continue;
            }
            result.add(new RecordSummary("turn", turn.turnId(), null, null,
                    null, null, null, "index.md", Hashes.sha256(indexContent),
                    "turnNo=" + turn.turnNo() + ",status=" + turn.status()));
            for (Map.Entry<String, String> entry : manifest.entrySet()) {
                result.add(recordSummaryForManifestFile(turn.turnId(), entry.getKey(),
                        entry.getValue()));
            }
        }
        return List.copyOf(result);
    }

    private List<RecordSummary> scanOpeningRecordSummaries() {
        try {
            return scanOpeningRecordSummariesUnchecked();
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "扫描开场记录摘要失败", failure);
        }
    }

    private List<RecordSummary> scanOpeningRecordSummariesUnchecked() throws IOException {
        Path root = openingsRoot();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<RecordSummary> result = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path child : stream.toList()) {
                if (Files.isSymbolicLink(child)
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String publicationId = child.getFileName().toString();
                try {
                    TurnRecordProjector.safeLogicalId(publicationId, "publicationId");
                } catch (RecordProjectionException unsafe) {
                    continue;
                }
                Path index = child.resolve("index.md");
                if (Files.isSymbolicLink(index)
                        || !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String markdown;
                try {
                    markdown = readText(index);
                    parseAndValidateOpeningIndex(child, markdown);
                } catch (RecordProjectionException inconsistentIndex) {
                    // FS-only 视图只暴露可验证的完整投影。
                    continue;
                }
                if (!lineValue(markdown, "kind").filter("OPENING"::equals).isPresent()
                        || !lineValue(markdown, "publicationId")
                        .filter(publicationId::equals).isPresent()) {
                    continue;
                }
                String publicPostId = lineValue(markdown, "publicPostId").orElse("");
                try {
                    TurnRecordProjector.safeLogicalId(publicPostId, "postId");
                } catch (RecordProjectionException unsafe) {
                    continue;
                }
                String publicationHash = lineValue(markdown, "contentHash").orElse("");
                String narrativeHash = lineValue(markdown, "publicNarrativeHash").orElse("");
                String status = lineValue(markdown, "status").orElse("UNKNOWN");
                result.add(new RecordSummary(
                        "opening", null, null, null, null, null, publicPostId,
                        "index.md", Hashes.sha256(markdown),
                        "publicationContentHash=" + publicationHash
                                + ",publicNarrativeHash=" + narrativeHash
                                + ",status=" + status,
                        publicationId));
            }
            return List.copyOf(result);
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "扫描开场记录失败", failure);
        }
    }

    private static RecordSummary recordSummaryForManifestFile(String turnId, String file,
                                                             String hash) {
        if ("public-narrative.md".equals(file)) {
            return new RecordSummary("public-narrative", turnId, null, null,
                    null, null, null, file, hash, "public narrative projection");
        }
        String[] parts = file.split("/", -1);
        String name = parts.length == 2 ? parts[1] : file;
        String stem = name.endsWith(".md")
                ? name.substring(0, name.length() - 3) : name;
        return switch (parts[0]) {
            case "players" -> new RecordSummary("player", turnId, stem, null,
                    null, null, null, file, hash, "player projection");
            case "actions" -> new RecordSummary("action", turnId, null, stem,
                    null, null, null, file, hash, "action projection");
            case "packages" -> new RecordSummary("package", turnId, null, null,
                    stem, null, null, file, hash, "package projection");
            case "batches" -> new RecordSummary("batch", turnId, null, null,
                    null, stem, null, file, hash, "batch projection");
            case "posts" -> new RecordSummary("post", turnId, null, null,
                    null, null, postIdFromFile(stem), file, hash, "post projection");
            default -> throw inconsistent("index manifest 含未知文件路径");
        };
    }

    private static Map<String, String> parseAndValidateIndex(Path turnDir,
                                                             String markdown) {
        return parseAndValidateIndex(turnDir, markdown, MANAGED_DIRECTORIES,
                Set.of("index.md", "public-narrative.md"));
    }

    private static Map<String, String> parseAndValidateOpeningIndex(Path openingDir,
                                                                    String markdown) {
        return parseAndValidateIndex(openingDir, markdown, List.of("posts"),
                Set.of("index.md", "public-narrative.md"));
    }

    private static Map<String, String> parseAndValidateIndex(Path recordDir, String markdown,
                                                             List<String> managedDirectories,
                                                             Set<String> allowedTopLevelFiles) {
        Map<String, String> manifest = parseManifest(markdown);
        Set<String> actual = actualGeneratedFiles(
                recordDir, managedDirectories, allowedTopLevelFiles);
        if (!actual.equals(manifest.keySet())) {
            throw inconsistent("index manifest 与目录文件集合不一致");
        }
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            Path file;
            String actualContent;
            try {
                file = resolveAllowedFile(recordDir, entry.getKey());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw inconsistent("manifest 文件不是普通文件");
                }
                actualContent = readText(file);
            } catch (RecordProjectionException failure) {
                if (RecordProjectionException.RECORD_INCONSISTENT.equals(failure.code())) {
                    throw failure;
                }
                throw inconsistent("manifest 文件缺失或不可读");
            }
            if (!entry.getValue().equals(Hashes.sha256(actualContent))) {
                throw inconsistent("manifest 文件 hash 不一致");
            }
        }
        return Map.copyOf(manifest);
    }

    private static Map<String, String> parseManifest(String indexContent) {
        String[] lines = indexContent.split("\\R", -1);
        int header = -1;
        for (int i = 0; i < lines.length; i++) {
            if ("## Content hashes".equals(lines[i].trim())) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            throw inconsistent("index 缺少 Content hashes");
        }
        Map<String, String> manifest = new LinkedHashMap<>();
        for (int i = header + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = HASH_LINE.matcher(line);
            if (!matcher.matches()) {
                throw inconsistent("index hash 行格式非法");
            }
            String file;
            try {
                file = normalizeManifestFile(matcher.group(1));
            } catch (RecordProjectionException failure) {
                if (RecordProjectionException.RECORD_INCONSISTENT.equals(failure.code())) {
                    throw failure;
                }
                throw inconsistent("index manifest 含不安全文件路径");
            }
            String hash = matcher.group(2);
            if (manifest.putIfAbsent(file, hash) != null) {
                throw inconsistent("index manifest 含重复文件");
            }
        }
        if (manifest.isEmpty()) {
            throw inconsistent("index manifest 为空");
        }
        return manifest;
    }

    private static String normalizeManifestFile(String raw) {
        String normalized = normalizeRelativeFile(raw);
        if ("index.md".equals(normalized)) {
            throw inconsistent("index.md 不得列入自身 manifest");
        }
        return normalized;
    }

    private static Set<String> actualGeneratedFiles(Path recordDir,
                                                     List<String> managedDirectories,
                                                     Set<String> allowedTopLevelFiles) {
        Set<String> actual = new LinkedHashSet<>();
        Path publicFile = recordDir.resolve("public-narrative.md");
        if (Files.isSymbolicLink(publicFile)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "public-narrative.md 不得是软链");
        }
        if (!Files.isRegularFile(publicFile, LinkOption.NOFOLLOW_LINKS)) {
            throw inconsistent("public-narrative.md 缺失或不是普通文件");
        }
        actual.add("public-narrative.md");
        for (String managed : managedDirectories) {
            Path dir = recordDir.resolve(managed);
            if (Files.isSymbolicLink(dir)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "记录子目录不得是软链: " + managed);
            }
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw inconsistent("记录子目录缺失或不是普通目录: " + managed);
            }
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    if (Files.isSymbolicLink(child)) {
                        throw new RecordProjectionException(
                                RecordProjectionException.UNSAFE_PATH,
                                "记录目录不得含软链: " + managed);
                    }
                    if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw inconsistent("记录目录含非普通文件: " + managed);
                    }
                    actual.add(managed + "/" + child.getFileName());
                }
            } catch (IOException failure) {
                throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                        "扫描记录子目录失败", failure);
            }
        }
        try (var stream = Files.list(recordDir)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (Files.isSymbolicLink(child)) {
                    throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                            "记录目录不得含软链");
                }
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (!allowedTopLevelFiles.contains(name)) {
                        throw inconsistent("记录目录含未索引文件");
                    }
                } else if (!managedDirectories.contains(name)) {
                    throw inconsistent("记录目录含未索引子目录");
                }
            }
        } catch (IOException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "扫描记录目录失败", failure);
        }
        return actual;
    }

    private static String readText(Path file) {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new RecordProjectionException(RecordProjectionException.FILE_NOT_FOUND,
                    "回合记录文件不存在");
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                        "回合记录文件超过读取上限");
            }
            byte[] bytes = Files.readAllBytes(file);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.indexOf('\u0000') >= 0) {
                throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                        "回合记录文件不是有效 UTF-8 文本");
            }
            return content;
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "读取回合记录失败", failure);
        }
    }

    private static RecordProjectionException inconsistent(String message) {
        return new RecordProjectionException(RecordProjectionException.RECORD_INCONSISTENT,
                message);
    }

    private static String postIdFromFile(String stem) {
        int dash = stem.lastIndexOf('-');
        return dash > 0 ? stem.substring(0, dash) : stem;
    }


    /**
     * FS-only 路径解析必须与 TurnRecordProjector.ensureRecordsRoot 等价：
     * dataDir/records 与 dataDir/records/&lt;child&gt; 均 NOFOLLOW 检查，存在路径的
     * realPath 必须仍在 dataDir realPath 内。records 父级软链一律 UNSAFE_PATH。
     */
    private Path turnsRoot() {
        return recordsRoot("turns");
    }

    private Path openingsRoot() {
        return recordsRoot("openings");
    }

    private Path recordsRoot(String child) {
        String label = "records/" + child;
        Path dataAbsolute = dataDir.toAbsolutePath().normalize();
        Path records = dataAbsolute.resolve("records");
        Path root = records.resolve(child);
        try {
            if (!Files.exists(dataAbsolute, LinkOption.NOFOLLOW_LINKS)) {
                return root.normalize();
            }
            Path dataReal = dataAbsolute.toRealPath();
            if (!Files.exists(records, LinkOption.NOFOLLOW_LINKS)) {
                return root.normalize();
            }
            if (Files.isSymbolicLink(records)
                    || !Files.isDirectory(records, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "records 必须是非软链普通目录");
            }
            Path recordsReal = records.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!recordsReal.startsWith(dataReal)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "records realPath 逃逸 dataDir realPath");
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return root.normalize();
            }
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        label + " 必须是非软链普通目录");
            }
            Path rootReal = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!rootReal.startsWith(dataReal) || !rootReal.startsWith(recordsReal)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        label + " realPath 逃逸 dataDir/records");
            }
            return rootReal;
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "读取 " + label + " 路径失败", failure);
        }
    }

    private static Optional<String> lineValue(String markdown, String name) {
        String prefix = "- " + name + ":";
        for (String line : markdown.split("\\R")) {
            if (line.startsWith(prefix)) {
                return Optional.of(line.substring(prefix.length()).trim()
                        .replace("`", ""));
            }
        }
        return Optional.empty();
    }

    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String item = part.trim().replace("`", "");
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            return Instant.EPOCH;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
