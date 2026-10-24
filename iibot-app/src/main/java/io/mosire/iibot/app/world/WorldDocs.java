package io.mosire.iibot.app.world;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.docs.AppendRequest;
import io.mosire.docs.DocAddr;
import io.mosire.docs.DocEntry;
import io.mosire.docs.DocException;
import io.mosire.docs.DocFile;
import io.mosire.docs.DocOpKind;
import io.mosire.docs.DocNamespaces;
import io.mosire.docs.DocReadResult;
import io.mosire.docs.DocViewer;
import io.mosire.docs.DocsApi;
import io.mosire.docs.DocsCapability;
import io.mosire.docs.EditOp;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.Origin;
import io.mosire.workspace.Resolvers;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspacePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据目录下的世界文档工作区。
 *
 * <p>用 workspace-mosire + docs-mosire 在 {@code <dataDir>/world} 建立装配期 {@code FILE}
 * 挂载（写 {@code world/bible.md}），并在 {@code <dataDir>/world/log} 建立 {@code LOG}
 * 挂载用于追加审计记录。调用者权限集显式声明三个面：{@code workspaces}（允许挂载的范围）、
 * {@code docs}（可读）、{@code docs-edit}（可写），并显式设置 destructiveAllowed/
 * sensitiveAllowed/readOnly。</p>
 *
 * <p><b>Docs 不暴露给玩家/秘书</b>：本类只被组合根用于宿主侧世界文档与审计；任务流工具面没有
 * 文档工具。</p>
 */
public final class WorldDocs implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorldDocs.class);
    private static final String HOLDER = "iibot-host";
    private static final String WORLD_MOUNT = "world";
    private static final String LOG_MOUNT = "world-log";
    private static final String BIBLE_PATH = "world/bible.md";
    private static final String AUDIT_PATH = "world-log/audit";
    private static final String AUDIT_ADDRESS = "startup-shutdown";

    private final DocsApi docsApi;
    private final WorkspaceCaller caller;
    private final Path worldDir;
    private final String promptVersion;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WorldDocs(DocsApi docsApi, WorkspaceCaller caller, Path worldDir,
                      String promptVersion) {
        this.docsApi = docsApi;
        this.caller = caller;
        this.worldDir = worldDir;
        this.promptVersion = promptVersion;
    }

    /**
     * 建立工作区并写入世界圣经；同一次启动内幂等（已有 bible 则只读取，不覆盖）。
     *
     * @param dataDir       数据目录（绝对、已归一）
     * @param promptVersion 世界提示版本
     * @param publicSummary 公开事实摘要（可为空字符串）
     * @param facts         全量事实，用于生成摘要；只把 PUBLIC 写入 bible
     */
    public static WorldDocs open(Path dataDir, String promptVersion, String publicSummary,
                                 List<WorldFact> facts) {
        Objects.requireNonNull(dataDir, "dataDir");
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion 不能为空");
        }
        Path worldDir = dataDir.toAbsolutePath().normalize().resolve("world");
        Path logDir = worldDir.resolve("log");
        try {
            Files.createDirectories(worldDir);
            Files.createDirectories(logDir);
        } catch (Exception e) {
            throw new IllegalStateException("创建世界文档目录失败: " + worldDir);
        }

        CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
        DocsCapability.register(catalog);
        ResourceScope worldScope = ResourceScope.ofDir(worldDir);
        Map<String, ResourceScope> scopes = new TreeMap<>();
        scopes.put(WorkspacePermissions.WORKSPACES, worldScope);
        scopes.put(DocNamespaces.DOCS, worldScope);
        scopes.put(DocNamespaces.DOCS_EDIT, worldScope);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .resourceScopes(new ResourceScopeMap(scopes))
                .build();
        WorkspaceCaller mountingCaller = new WorkspaceCaller(HOLDER, permissions);
        DefaultWorkspaceRegistry registry = DefaultWorkspaceRegistry.create(catalog);
        registry.mount(new Mount(new MountName(WORLD_MOUNT), HOLDER, HOLDER, worldDir,
                Resolvers.direct(), Kind.FILE, Set.of(DocsCapability.ID), Origin.ASSEMBLY),
                mountingCaller);
        registry.mount(new Mount(new MountName(LOG_MOUNT), HOLDER, HOLDER, logDir,
                Resolvers.direct(), Kind.LOG, Set.of(DocsCapability.ID), Origin.ASSEMBLY),
                mountingCaller);
        AgentPermissionSet landed = registry.grant(HOLDER, permissions);
        WorkspaceCaller caller = new WorkspaceCaller(HOLDER, landed);
        DocsApi api = new DocsApi(registry);
        WorldDocs worldDocs = new WorldDocs(api, caller, worldDir, promptVersion);
        worldDocs.ensureBible(promptVersion, publicSummary, facts);
        worldDocs.appendAudit("startup", "promptVersion=" + promptVersion);
        LOG.info("世界文档工作区就绪: dir={} promptVersion={}", worldDir, promptVersion);
        return worldDocs;
    }

    private void ensureBible(String promptVersion, String publicSummary, List<WorldFact> facts) {
        String content = renderBible(promptVersion, publicSummary, facts);
        try {
            DocFile created = docsApi.createFile(BIBLE_PATH, caller, content);
            LOG.info("世界圣经已创建: virtualPath={} lines={}", created.virtualPath(), created.lines());
        } catch (DocException exists) {
            if (!DocException.DOC_EXISTS.equals(exists.code())) {
                throw exists;
            }
            // 重启：保留既有世界文档，不静默覆盖；追加一条启动审计即可。
            LOG.info("世界圣经已存在，保留原文件: virtualPath={}", BIBLE_PATH);
        }
    }

    /**
     * 运行期装载世界事实后刷新世界圣经投影。
     *
     * <p>先按最新事实渲染完整内容，再经 Docs 的受控 editFile 全量替换；失败向上抛出，
     * 由调用方决定是否记录。调用方不得在事件/世界状态提交前把它当作事实源。</p>
     */
    public synchronized void refreshWorld(List<WorldFact> facts) {
        String content = renderBible(promptVersion, publicSummary(facts), facts);
        try {
            DocReadResult current = docsApi.readFile(BIBLE_PATH, caller, "");
            int lastLine = Math.max(1, Math.toIntExact(current.totalLines()));
            docsApi.editFile(BIBLE_PATH, caller, List.of(EditOp.of(
                    DocAddr.range(1, lastLine), DocOpKind.REPLACE, content)));
            LOG.info("世界圣经已刷新: facts={} lines={}",
                    facts == null ? 0 : facts.size(), lastLine);
        } catch (RuntimeException failure) {
            LOG.warn("世界圣经刷新失败 type={}", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** 读取 world/bible.md 全文。 */
    public String bibleText() {
        DocReadResult result = docsApi.readFile(BIBLE_PATH, caller, "");
        return result.content();
    }

    /** 追加一条宿主审计日志（关闭前也可调用）。 */
    public DocEntry appendAudit(String key, String value) {
        return docsApi.append(AUDIT_PATH, caller, AppendRequest.of(AUDIT_ADDRESS,
                Instant.now().toEpochMilli(), key, value));
    }

    /** 读取本进程写入的全部世界审计条目（用于测试/诊断）。 */
    public List<DocEntry> readAudit() {
        return docsApi.read(AUDIT_PATH, caller, DocViewer.seesAll(HOLDER), AUDIT_ADDRESS);
    }

    /** 世界目录物理路径（仅宿主日志/测试；不暴露给模型）。 */
    public Path worldDir() {
        return worldDir;
    }

    /** 关闭前追加 shutdown 审计；幂等。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            appendAudit("shutdown", "host shutdown at " + Instant.now());
        } catch (RuntimeException e) {
            LOG.warn("关闭时追加世界审计失败 error={}", e.getClass().getSimpleName());
        }
    }

    private static String renderBible(String promptVersion, String publicSummary,
                                      List<WorldFact> facts) {
        StringBuilder text = new StringBuilder();
        text.append("# 世界圣经\n\n")
                .append("- promptVersion: ").append(promptVersion).append('\n')
                .append("- generatedAt: ").append(Instant.now()).append('\n')
                .append("- visibility: 本文件仅供宿主审计/世界文档存储，不直接注入玩家秘书上下文\n\n")
                .append("## 公开事实摘要\n\n");
        String summary = publicSummary;
        if (summary == null || summary.isBlank()) {
            StringBuilder generated = new StringBuilder();
            if (facts != null) {
                for (WorldFact fact : facts) {
                    if (fact.visibility() == io.mosire.iibot.taskflow.Visibility.PUBLIC) {
                        generated.append("- [").append(fact.factId()).append("] ")
                                .append(fact.content()).append('\n');
                    }
                }
            }
            summary = generated.toString();
        }
        if (summary.isBlank()) {
            text.append("- 无公开事实\n");
        } else {
            text.append(summary);
            if (!summary.endsWith("\n")) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    /** 提取公开事实摘要，供装配层写入 bible。 */
    public static String publicSummary(List<WorldFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (WorldFact fact : facts) {
            if (fact.visibility() == io.mosire.iibot.taskflow.Visibility.PUBLIC) {
                text.append("- [").append(fact.factId()).append("] ")
                        .append(fact.content()).append('\n');
            }
        }
        return text.toString();
    }
}
