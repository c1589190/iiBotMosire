package io.mosire.iibot.taskflow.workspace;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.docs.DocException;
import io.mosire.docs.DocNamespaces;
import io.mosire.docs.DocsApi;
import io.mosire.docs.DocsCapability;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.Origin;
import io.mosire.workspace.Resolvers;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceException;
import io.mosire.workspace.WorkspacePermissions;
import io.mosire.workspace.WorkspaceRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每玩家一个的秘书 Workspace/Docs 工作区基础层。
 *
 * <p>每个玩家在 {@code <dataDir>/secretaries/<playerId>/} 下拥有四个逻辑根：
 * {@code public/}、{@code player/}、{@code gm/}、{@code notes/}。本类用
 * workspace-mosire 的 {@link WorkspaceRegistry} 做唯一的虚拟路径解析与权限落格，再经
 * docs-mosire 的 {@link DocsApi} 做文件读写；调用方不会拿到可绕开 Docs 纪律的读写入口。</p>
 *
 * <p><b>隔离</b>：每个玩家有独立的注册表、{@code FILE} 挂载与 {@link DocsApi}。未知玩家
 * 在进入 Docs 之前即拒绝，因此一个玩家的逻辑路径无法解析到另一个玩家的物理目录。</p>
 *
 * <p><b>路径</b>：所有公开路径参数都只接受相对 Docs 路径，且首段必须属于
 * {@link #ROOT_DIRECTORIES}。绝对路径、反斜杠、空段、{@code .}/{@code ..} 段会被拒；
 * 实际 IO 由 workspace-mosire + docs-mosire 的 no-follow/containment 检查兜底，软链
 * 一律 fail-closed。</p>
 *
 * <p><b>面向模型/Agent 的工具不由本类直接调用</b>：本类是宿主与工具共同的领域端口。
 * {@link #rootFor(String)} 只用于测试或宿主诊断，不得进入工具结果。</p>
 */
public final class SecretaryWorkspaceStore {

    /** 秘书工作区工具声明的资源命名空间。 */
    public static final String RESOURCE_NAMESPACE = "secretary-workspace";

    /** 四个允许的逻辑根，顺序也用于空前缀列举。 */
    public static final List<String> ROOT_DIRECTORIES =
            List.of("public", "player", "gm", "notes");

    /** 单篇 material / note 的最大字符数。 */
    public static final int MAX_CONTENT_CHARS = 64 * 1024;

    /** note 的最大字符数（比普通材料更紧，避免秘书把工作区当聊天日志）。 */
    public static final int MAX_NOTE_CHARS = 16 * 1024;

    private static final String MOUNT_NAME = "secretary";
    private static final String HOLDER = "secretary-workspace-host";

    private final Path dataDir;
    private final ConcurrentMap<String, PlayerWorkspace> workspaces;

    private SecretaryWorkspaceStore(Path dataDir, Map<String, PlayerWorkspace> workspaces) {
        this.dataDir = dataDir;
        this.workspaces = new ConcurrentHashMap<>(workspaces);
    }

    /**
     * 建立所有玩家的工作区目录与 FILE 挂载。重复调用只做幂等目录检查，不覆盖已有文档。
     *
     * @param dataDir   宿主数据目录
     * @param playerIds 已认证玩家 ID；未知/非法 ID 在 open 时即 fail-closed
     */
    public static SecretaryWorkspaceStore open(Path dataDir, Collection<String> playerIds) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(playerIds, "playerIds");

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String playerId : playerIds) {
            ids.add(requireValidPlayerId(playerId));
        }

        Path base = prepareDataDirectory(dataDir);
        Path secretaries = ensureDirectory(base, base.resolve("secretaries"));
        Map<String, PlayerWorkspace> mounted = new LinkedHashMap<>();
        for (String playerId : ids) {
            mounted.put(playerId, createPlayerWorkspace(base, secretaries, playerId));
        }
        return new SecretaryWorkspaceStore(base, mounted);
    }

    /**
     * 幂等建立/登记一个运行期新增玩家的隔离工作区。
     *
     * <p>已存在的玩家不会重复创建目录、覆盖文档或替换其注册表；未知/非法 playerId
     * 在触碰文件系统前 fail-closed。目录/软链安全规则与 {@link #open(Path, Collection)}
     * 完全一致。方法同步以保证并发动态注册不会创建两套挂载。</p>
     */
    public synchronized void ensurePlayer(String playerId) {
        String key = requireValidPlayerId(playerId);
        if (workspaces.containsKey(key)) {
            return;
        }
        Path base = prepareDataDirectory(dataDir);
        Path secretaries = ensureDirectory(base, base.resolve("secretaries"));
        workspaces.put(key, createPlayerWorkspace(base, secretaries, key));
    }

    private static PlayerWorkspace createPlayerWorkspace(Path base, Path secretaries,
                                                          String playerId) {
        Path root = ensureDirectory(base, secretaries.resolve(playerId));
        for (String allowedRoot : ROOT_DIRECTORIES) {
            ensureDirectory(base, root.resolve(allowedRoot));
        }
        return mountWorkspace(root);
    }

    /**
     * 列出某个前缀的直接子项。空前缀列出四个根目录；返回的是相对 Docs 路径。
     */
    public List<String> list(String playerId, String prefix) {
        PlayerWorkspace workspace = requireKnown(playerId);
        String relative = normalizePath(prefix, true);
        String virtual = virtualPath(relative);
        try {
            List<String> entries = workspace.registry().list(virtual, workspace.caller());
            List<String> relativeEntries = new ArrayList<>(entries.size());
            for (String entry : entries) {
                String stripped = stripMountPrefix(entry);
                if (relative.isEmpty()) {
                    // 根只允许四个逻辑目录；物理目录里若出现额外条目，不把它带到工具/模型面。
                    if (ROOT_DIRECTORIES.contains(stripped)) {
                        relativeEntries.add(stripped);
                    }
                } else {
                    relativeEntries.add(stripped);
                }
            }
            return List.copyOf(relativeEntries);
        } catch (WorkspaceException failure) {
            throw invalid("工作区目录不可列出（fail-closed）");
        }
    }

    /**
     * 读取一个文件；不存在返回 {@link Optional#empty()}，越权/软链/IO 错误则抛出。
     */
    public Optional<String> read(String playerId, String path) {
        PlayerWorkspace workspace = requireKnown(playerId);
        String relative = normalizePath(path, false);
        try {
            return Optional.of(workspace.docsApi().readFile(virtualPath(relative),
                    workspace.caller(), "").content());
        } catch (DocException failure) {
            if (DocException.DOC_NOT_FOUND.equals(failure.code())) {
                return Optional.empty();
            }
            throw invalid("文档读取失败（fail-closed）");
        } catch (WorkspaceException failure) {
            throw invalid("文档路径不可读（fail-closed）");
        }
    }

    /**
     * 追加一篇秘书笔记。只能写 {@code notes/}，文件名形如
     * {@code note-<epochMillis>-<uuid>.md}，两次调用不会覆盖。
     */
    public void appendNote(String playerId, String text) {
        PlayerWorkspace workspace = requireKnown(playerId);
        requireContent(text, MAX_NOTE_CHARS, "note");

        for (int attempt = 0; attempt < 4; attempt++) {
            String fileName = "note-" + Instant.now().toEpochMilli() + "-"
                    + UUID.randomUUID().toString().replace("-", "") + ".md";
            String relative = normalizePath("notes/" + fileName, false);
            try {
                workspace.docsApi().createFile(virtualPath(relative), workspace.caller(), text);
                return;
            } catch (DocException failure) {
                if (DocException.DOC_EXISTS.equals(failure.code()) && attempt < 3) {
                    continue;
                }
                throw invalid("笔记写入失败（fail-closed）");
            } catch (WorkspaceException failure) {
                throw invalid("笔记路径不可写（fail-closed）");
            }
        }
        throw invalid("笔记写入失败（fail-closed）");
    }

    /**
     * GM 下发材料。只允许写 {@code gm/} 或 {@code player/}，且不允许静默覆盖已有文件。
     */
    public void writeGmMaterial(String playerId, String path, String content) {
        PlayerWorkspace workspace = requireKnown(playerId);
        String relative = normalizePath(path, false);
        String root = rootOf(relative);
        if (!"gm".equals(root) && !"player".equals(root)) {
            throw invalid("GM 只能写入 gm/ 或 player/");
        }
        if (segmentsOf(relative).size() < 2) {
            throw invalid("材料路径必须指向 gm/ 或 player/ 下的文件");
        }
        requireContent(content, MAX_CONTENT_CHARS, "material");
        try {
            workspace.docsApi().createFile(virtualPath(relative), workspace.caller(), content);
        } catch (DocException failure) {
            if (DocException.DOC_EXISTS.equals(failure.code())) {
                throw invalid("材料路径已存在（拒绝静默覆盖）");
            }
            throw invalid("材料写入失败（fail-closed）");
        } catch (WorkspaceException failure) {
            throw invalid("材料路径不可写（fail-closed）");
        }
    }

    /** 物理工作区根；仅测试/宿主诊断使用，不进入 Agent 工具结果。 */
    public Path rootFor(String playerId) {
        return requireKnown(playerId).root();
    }

    private PlayerWorkspace requireKnown(String playerId) {
        String key = requireValidPlayerId(playerId);
        PlayerWorkspace workspace = workspaces.get(key);
        if (workspace == null) {
            throw invalid("未知玩家工作区");
        }
        return workspace;
    }

    private static PlayerWorkspace mountWorkspace(Path root) {
        CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
        DocsCapability.register(catalog);
        ResourceScope rootScope = ResourceScope.ofDir(root);

        Map<String, ResourceScope> scopes = new TreeMap<>();
        scopes.put(WorkspacePermissions.WORKSPACES, rootScope);
        scopes.put(DocNamespaces.DOCS, rootScope);
        scopes.put(DocNamespaces.DOCS_EDIT, rootScope);

        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .resourceScopes(new ResourceScopeMap(scopes))
                .build();
        WorkspaceCaller mountingCaller = new WorkspaceCaller(HOLDER, permissions);
        DefaultWorkspaceRegistry registry = DefaultWorkspaceRegistry.create(catalog);
        registry.mount(new Mount(new MountName(MOUNT_NAME), HOLDER, HOLDER, root,
                Resolvers.direct(), Kind.FILE, Set.of(DocsCapability.ID), Origin.ASSEMBLY),
                mountingCaller);
        AgentPermissionSet landed = registry.grant(HOLDER, permissions);
        WorkspaceCaller caller = new WorkspaceCaller(HOLDER, landed);
        return new PlayerWorkspace(root, registry, new DocsApi(registry), caller);
    }

    private static Path prepareDataDirectory(Path dataDir) {
        Path requested = dataDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(requested);
            return requested.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("无法建立工作区数据目录");
        }
    }

    /**
     * 逐级建立目录并拒绝软链。base 必须是已解析的真实根。
     */
    private static Path ensureDirectory(Path base, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            throw invalid("工作区目录越界（fail-closed）");
        }
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized)
                        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("工作区目录不是安全目录（fail-closed）");
                }
                Path real = normalized.toRealPath();
                if (!real.startsWith(base)) {
                    throw invalid("工作区目录真实路径越界（fail-closed）");
                }
                return normalized;
            }

            Path parent = normalized.getParent();
            if (parent == null) {
                throw invalid("工作区目录缺少父目录（fail-closed）");
            }
            ensureDirectory(base, parent);
            Files.createDirectory(normalized);
            if (Files.isSymbolicLink(normalized)) {
                throw invalid("工作区目录出现软链（fail-closed）");
            }
            return normalized;
        } catch (IOException failure) {
            throw new IllegalStateException("无法建立工作区目录");
        }
    }

    private static String requireValidPlayerId(String playerId) {
        // playerId 只作为 secretaries/ 下的单段目录名；不引入 Docs 路径语法，也不允许
        // 任何可以改变目录层级的字符。其余合法配置字符（含非 ASCII）保持原样。
        if (playerId == null || playerId.isBlank() || playerId.length() > 128
                || ".".equals(playerId) || "..".equals(playerId)
                || playerId.indexOf('/') >= 0 || playerId.indexOf('\\') >= 0
                || playerId.indexOf('\0') >= 0
                || playerId.chars().anyMatch(Character::isISOControl)
                || playerId.matches("^[A-Za-z]:.*")) {
            throw invalid("玩家 ID 非法");
        }
        return playerId;
    }

    private static void requireContent(String content, int limit, String label) {
        if (content == null) {
            throw invalid(label + " 内容不能为 null");
        }
        if (content.length() > limit) {
            throw invalid(label + " 内容超过长度上限");
        }
    }

    /**
     * 把外部路径规范成挂载内相对路径。只接受 {@code /} 分隔，禁止绝对路径、反斜杠、
     * 空段、{@code .}/{@code ..} 段和非白名单根。
     */
    private static String normalizePath(String raw, boolean allowRoot) {
        if (raw == null || raw.isBlank()) {
            if (allowRoot) {
                return "";
            }
            throw invalid("路径不能为空");
        }
        if (raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0
                || raw.startsWith("/") || raw.startsWith("~")
                || raw.matches("^[A-Za-z]:.*")) {
            throw invalid("路径必须是 Docs 内的相对路径");
        }
        String[] segments = raw.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("路径不得包含空段、. 或 ..");
            }
        }
        if (!ROOT_DIRECTORIES.contains(segments[0])) {
            throw invalid("路径必须位于 public/、player/、gm/ 或 notes/");
        }
        return String.join("/", segments);
    }

    private static String rootOf(String relative) {
        return segmentsOf(relative).get(0);
    }

    private static List<String> segmentsOf(String relative) {
        return List.of(relative.split("/", -1));
    }

    private static String virtualPath(String relative) {
        return relative.isEmpty() ? MOUNT_NAME : MOUNT_NAME + "/" + relative;
    }

    /** workspace-mosire 的列表投影是虚拟路径；本端口只暴露 Docs 根内相对路径。 */
    private static String stripMountPrefix(String entry) {
        String prefix = MOUNT_NAME + "/";
        return entry.startsWith(prefix) ? entry.substring(prefix.length()) : entry;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record PlayerWorkspace(Path root, WorkspaceRegistry registry, DocsApi docsApi,
                                   WorkspaceCaller caller) {
        private PlayerWorkspace {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(docsApi, "docsApi");
            Objects.requireNonNull(caller, "caller");
        }
    }
}
