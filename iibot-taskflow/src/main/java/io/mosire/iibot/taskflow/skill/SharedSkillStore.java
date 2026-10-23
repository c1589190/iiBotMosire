package io.mosire.iibot.taskflow.skill;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.docs.DocAddr;
import io.mosire.docs.DocException;
import io.mosire.docs.DocNamespaces;
import io.mosire.docs.DocOpKind;
import io.mosire.docs.DocReadResult;
import io.mosire.docs.DocsApi;
import io.mosire.docs.DocsCapability;
import io.mosire.docs.EditOp;
import io.mosire.iibot.taskflow.Hashes;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 全局共享 Skill / 公共文档只读区。
 *
 * <p>物理布局固定为 {@code <dataDir>/shared-skills/{public,skills}}。整个共享区由一个
 * workspace-mosire {@code FILE} 挂载承载，所有读写都经 docs-mosire 的 {@link DocsApi}；
 * 本类不提供绕开 Docs 纪律的文件 IO 入口。路径参数只接受 {@code public/}、{@code skills/}
 * 根下的相对 Docs 路径，绝对路径、反斜杠、空段、{@code .}/{@code ..}、{@code ~}、
 * Windows drive 与软链逃逸一律 fail-closed。</p>
 *
 * <p>秘书与 GM aide 工具只调用 {@link #list(String)} / {@link #read(String)}；写方法为
 * 宿主/MCP 后续切片提供，当前 Agent 工具面没有写入口。文档正文不进入日志或异常文案，
 * 写结果只暴露逻辑路径与内容 hash。</p>
 */
public final class SharedSkillStore {

    /** 共享 Skill 工具声明的资源命名空间（read-only）。 */
    public static final String RESOURCE_NAMESPACE = "shared-skills";

    /** 两个允许的逻辑根。 */
    public static final List<String> ROOT_DIRECTORIES = List.of("public", "skills");

    /** 单篇共享文档的最大 UTF-8 字节数。 */
    public static final int MAX_CONTENT_BYTES = 64 * 1024;

    /** 兼容按字符计数的调用方；真正的落盘上限仍以 UTF-8 字节为准。 */
    public static final int MAX_CONTENT_CHARS = MAX_CONTENT_BYTES;

    private static final String MOUNT_NAME = "shared-skills";
    private static final String HOLDER = "shared-skills-host";

    private final Path root;
    private final WorkspaceRegistry registry;
    private final DocsApi docsApi;
    private final WorkspaceCaller caller;

    private SharedSkillStore(Path root, WorkspaceRegistry registry, DocsApi docsApi,
                             WorkspaceCaller caller) {
        this.root = root;
        this.registry = registry;
        this.docsApi = docsApi;
        this.caller = caller;
    }

    /**
     * 幂等建立 {@code dataDir/shared-skills/{public,skills}} 与单个 FILE 挂载。
     */
    public static SharedSkillStore open(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        Path base = prepareDataDirectory(dataDir);
        Path root = ensureDirectory(base, base.resolve(MOUNT_NAME));
        for (String allowedRoot : ROOT_DIRECTORIES) {
            ensureDirectory(base, root.resolve(allowedRoot));
        }
        Mounted mounted = mountWorkspace(root);
        return new SharedSkillStore(root, mounted.registry(), mounted.docsApi(), mounted.caller());
    }

    /**
     * 列出某个相对前缀的直接子项。空前缀列出两个允许根；返回逻辑相对 Docs 路径。
     */
    public List<String> list(String prefix) {
        String relative = normalizePath(prefix, true);
        try {
            List<String> entries = registry.list(virtualPath(relative), caller);
            List<String> result = new ArrayList<>(entries.size());
            for (String entry : entries) {
                String stripped = stripMountPrefix(entry);
                if (relative.isEmpty()) {
                    // 物理根里多出来的目录不进入工具/模型面。
                    if (ROOT_DIRECTORIES.contains(stripped)) {
                        result.add(stripped);
                    }
                } else {
                    result.add(stripped);
                }
            }
            return List.copyOf(result);
        } catch (WorkspaceException failure) {
            throw invalid("共享 Skill 目录不可列出（fail-closed）");
        }
    }

    /**
     * 按相对路径读取一个共享文档；不存在返回 {@link Optional#empty()}。
     */
    public Optional<String> read(String path) {
        String relative = requireFilePath(path, "读取");
        try {
            DocReadResult result = docsApi.readFile(virtualPath(relative), caller, "");
            return Optional.of(result.content());
        } catch (DocException failure) {
            if (DocException.DOC_NOT_FOUND.equals(failure.code())) {
                return Optional.empty();
            }
            throw invalid("共享 Skill 文档读取失败（fail-closed）");
        } catch (WorkspaceException failure) {
            throw invalid("共享 Skill 文档路径不可读（fail-closed）");
        }
    }

    /**
     * 以 GM 身份写入共享文档。默认拒绝覆盖；仅 {@code replace=true} 才允许替换。
     *
     * <p>返回对象只包含逻辑路径、内容 hash 与 created/replaced 状态；正文不进入返回消息或
     * 日志。</p>
     */
    public WriteResult writeGm(String path, String content, boolean replace) {
        String relative = requireFilePath(path, "写入");
        requireContent(content);
        String canonicalContent = canonicalize(content);
        byte[] bytes = canonicalContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw invalid("共享 Skill 文档超过 64 KiB 上限");
        }
        String contentHash = Hashes.sha256(canonicalContent);

        if (!replace) {
            try {
                docsApi.createFile(virtualPath(relative), caller, canonicalContent);
                return new WriteResult(relative, contentHash, true, false);
            } catch (DocException failure) {
                if (DocException.DOC_EXISTS.equals(failure.code())) {
                    throw invalid("共享 Skill 文档已存在（默认拒绝覆盖）");
                }
                throw invalid("共享 Skill 文档写入失败（fail-closed）");
            } catch (WorkspaceException failure) {
                throw invalid("共享 Skill 文档路径不可写（fail-closed）");
            }
        }

        DocReadResult existing;
        try {
            existing = docsApi.readFile(virtualPath(relative), caller, "");
        } catch (DocException failure) {
            if (DocException.DOC_NOT_FOUND.equals(failure.code())) {
                try {
                    docsApi.createFile(virtualPath(relative), caller, canonicalContent);
                    return new WriteResult(relative, contentHash, true, false);
                } catch (DocException createFailure) {
                    if (DocException.DOC_EXISTS.equals(createFailure.code())) {
                        // 与读取之间的并发创建：重走一次替换路径；仍失败则具名拒绝。
                        return replaceExisting(relative, canonicalContent, contentHash);
                    }
                    throw invalid("共享 Skill 文档写入失败（fail-closed）");
                } catch (WorkspaceException failure2) {
                    throw invalid("共享 Skill 文档路径不可写（fail-closed）");
                }
            }
            throw invalid("共享 Skill 文档读取失败（fail-closed）");
        } catch (WorkspaceException failure) {
            throw invalid("共享 Skill 文档路径不可读（fail-closed）");
        }
        return replaceExisting(relative, canonicalContent, contentHash, existing);
    }

    /** 物理共享区根；仅测试/宿主诊断使用，不进入 Agent 工具结果。 */
    public Path rootForDiagnostics() {
        return root;
    }

    private WriteResult replaceExisting(String relative, String content, String contentHash) {
        try {
            DocReadResult current = docsApi.readFile(virtualPath(relative), caller, "");
            return replaceExisting(relative, content, contentHash, current);
        } catch (DocException failure) {
            if (DocException.DOC_NOT_FOUND.equals(failure.code())) {
                throw invalid("共享 Skill 文档在替换期间消失（fail-closed）");
            }
            throw invalid("共享 Skill 文档读取失败（fail-closed）");
        } catch (WorkspaceException failure) {
            throw invalid("共享 Skill 文档路径不可读（fail-closed）");
        }
    }

    private WriteResult replaceExisting(String relative, String content, String contentHash,
                                        DocReadResult current) {
        try {
            if (current.totalLines() == 0) {
                if (content.isEmpty()) {
                    return new WriteResult(relative, contentHash, false, true);
                }
                // DocsApi 的 FILE 编辑以行为寻址，空文件没有可替换的行。这里不能退化成
                // 直接文件 IO（那会绕过 Docs 纪律），因此清晰 fail-closed。
                throw invalid("空共享文档无法通过 Docs 行寻址替换");
            }
            DocOpKind operation = content.isEmpty() ? DocOpKind.DELETE : DocOpKind.REPLACE;
            docsApi.editFile(virtualPath(relative), caller, List.of(
                    EditOp.of(DocAddr.range(1, (int) current.totalLines()),
                            operation, content)));
            return new WriteResult(relative, contentHash, false, true);
        } catch (DocException failure) {
            if (DocException.DOC_NOT_FOUND.equals(failure.code())) {
                throw invalid("共享 Skill 文档在替换期间消失（fail-closed）");
            }
            throw invalid("共享 Skill 文档替换失败（fail-closed）");
        } catch (WorkspaceException failure) {
            throw invalid("共享 Skill 文档路径不可写（fail-closed）");
        }
    }

    private static Mounted mountWorkspace(Path root) {
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
        return new Mounted(registry, new DocsApi(registry), caller);
    }

    private static Path prepareDataDirectory(Path dataDir) {
        Path requested = dataDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(requested);
            return requested.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("无法建立共享 Skill 数据目录");
        }
    }

    /**
     * 逐级建立目录并拒绝软链。base 必须是已解析的真实根。
     */
    private static Path ensureDirectory(Path base, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            throw invalid("共享 Skill 目录越界（fail-closed）");
        }
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized)
                        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("共享 Skill 目录不是安全目录（fail-closed）");
                }
                Path real = normalized.toRealPath();
                if (!real.startsWith(base)) {
                    throw invalid("共享 Skill 目录真实路径越界（fail-closed）");
                }
                return normalized;
            }

            Path parent = normalized.getParent();
            if (parent == null) {
                throw invalid("共享 Skill 目录缺少父目录（fail-closed）");
            }
            ensureDirectory(base, parent);
            Files.createDirectory(normalized);
            if (Files.isSymbolicLink(normalized)) {
                throw invalid("共享 Skill 目录出现软链（fail-closed）");
            }
            return normalized;
        } catch (IOException failure) {
            throw new IllegalStateException("无法建立共享 Skill 目录");
        }
    }

    /**
     * 规范成挂载内相对路径。只接受 {@code /} 分隔，禁止绝对路径、反斜杠、NUL、控制字符、
     * {@code ~}、Windows drive、空段和 {@code .}/{@code ..}；首段必须属于两个允许根。
     */
    private static String normalizePath(String raw, boolean allowRoot) {
        if (raw == null || raw.isBlank()) {
            if (allowRoot) {
                return "";
            }
            throw invalid("共享 Skill 路径不能为空");
        }
        if (raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0
                || raw.startsWith("/") || raw.startsWith("~")
                || raw.matches("^[A-Za-z]:.*")
                || raw.chars().anyMatch(Character::isISOControl)) {
            throw invalid("路径必须是共享 Skill 区内的相对 Docs 路径");
        }
        String[] segments = raw.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("路径不得包含空段、. 或 ..");
            }
        }
        if (!ROOT_DIRECTORIES.contains(segments[0])) {
            throw invalid("路径必须位于 public/ 或 skills/");
        }
        return String.join("/", segments);
    }

    private static String requireFilePath(String path, String action) {
        String relative = normalizePath(path, false);
        if (relative.split("/", -1).length < 2) {
            throw invalid("共享 Skill 文档" + action + "路径必须指向 public/ 或 skills/ 下的文件");
        }
        return relative;
    }

    private static void requireContent(String content) {
        if (content == null) {
            throw invalid("共享 Skill 文档内容不能为 null");
        }
    }

    private static String canonicalize(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n";
        }
        return normalized;
    }

    private static String virtualPath(String relative) {
        return relative.isEmpty() ? MOUNT_NAME : MOUNT_NAME + "/" + relative;
    }

    private static String stripMountPrefix(String entry) {
        String prefix = MOUNT_NAME + "/";
        return entry.startsWith(prefix) ? entry.substring(prefix.length()) : entry;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /**
     * GM 写入结果。{@code created} 与 {@code replaced} 恰好一个为 true；正文与物理路径
     * 都不在这里。
     */
    public record WriteResult(String path, String contentHash, boolean created, boolean replaced) {

        public WriteResult {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path 不能为空");
            }
            if (contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException("contentHash 不能为空");
            }
            if (created == replaced) {
                throw new IllegalArgumentException("created/replaced 必须恰好一个为 true");
            }
        }

        /** {@code created} 或 {@code replaced}。 */
        public String status() {
            return created ? "created" : "replaced";
        }
    }

    private record Mounted(WorkspaceRegistry registry, DocsApi docsApi, WorkspaceCaller caller) {
        private Mounted {
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(docsApi, "docsApi");
            Objects.requireNonNull(caller, "caller");
        }
    }
}
