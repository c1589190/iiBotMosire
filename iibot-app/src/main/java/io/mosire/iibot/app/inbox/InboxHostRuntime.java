package io.mosire.iibot.app.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.client.InboxModels;
import io.mosire.inbox.client.TokenStore;
import io.mosire.inbox.embedded.EmbeddedInboxOptions;
import io.mosire.inbox.embedded.EmbeddedInboxServer;
import io.mosire.inbox.mcptools.InboxClientMcpTools;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * app 进程内 InboxMosire Mode A 生命周期与工具面。
 *
 * <p>embedded server 只绑定随机回环端口；程序 MCP token 与 DSH wake token 写入
 * 显式持久 dataDir 的 0600 文件。返回值中的 BindingDescriptor 固定只含
 * inboxId/apiBaseUrl/adapterTokenRef/channels/label，不含任何 raw token。</p>
 */
public final class InboxHostRuntime implements AutoCloseable {

    public static final String PROGRAM_TOKEN_FILE = "program-mcp.token";
    public static final String WAKE_TOKEN_FILE = "dsh-wake.token";
    public static final String DESCRIPTOR_FILE = "binding-descriptor.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROGRAM_CREDENTIAL_LABEL = "iibot-program-mcp";
    private static final Set<String> PROGRAM_SCOPES = Set.of(
            "items:write", "items:read", "items:manage", "status:read", "inbox:occupy");
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of(
            "inboxId", "apiBaseUrl", "adapterTokenRef", "channels", "label");

    private final EmbeddedInboxServer server;
    private final InboxClient programClient;
    private final InboxModels.BindingDescriptor descriptor;
    private final Path descriptorPath;
    private final Path programTokenPath;
    private final Path wakeTokenPath;
    private final InboxNotificationBridge notificationBridge;
    private final List<AgentTool> mcpTools;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private InboxHostRuntime(
            EmbeddedInboxServer server,
            InboxClient programClient,
            InboxModels.BindingDescriptor descriptor,
            Path descriptorPath,
            Path programTokenPath,
            Path wakeTokenPath,
            InboxNotificationBridge notificationBridge,
            List<AgentTool> mcpTools) {
        this.server = server;
        this.programClient = programClient;
        this.descriptor = descriptor;
        this.descriptorPath = descriptorPath;
        this.programTokenPath = programTokenPath;
        this.wakeTokenPath = wakeTokenPath;
        this.notificationBridge = notificationBridge;
        this.mcpTools = List.copyOf(mcpTools);
    }

    /**
     * 启动 embedded server、签发 program/wake token、持久化 descriptor 并装配通知桥。
     * 任一 token 写入、descriptor 字段或 server 生命周期失败均 fail-closed。
     */
    public static InboxHostRuntime start(AppConfig.Inbox config,
                                         TicketService ticketService,
                                         PostService postService) {
        Objects.requireNonNull(config, "config");
        if (!config.enabled()) {
            throw new IllegalStateException("inbox.enabled=false，不能启动 InboxHostRuntime");
        }
        Objects.requireNonNull(ticketService, "ticketService");
        Objects.requireNonNull(postService, "postService");

        Path dataDir = config.dataDir();
        Path programTokenPath = dataDir.resolve(PROGRAM_TOKEN_FILE).toAbsolutePath().normalize();
        Path wakeTokenPath = dataDir.resolve(WAKE_TOKEN_FILE).toAbsolutePath().normalize();
        Path descriptorPath = dataDir.resolve(DESCRIPTOR_FILE).toAbsolutePath().normalize();
        EmbeddedInboxServer server = null;
        InboxNotificationBridge bridge = null;
        try {
            server = EmbeddedInboxServer.start(EmbeddedInboxOptions.builder()
                    .dataDir(dataDir)
                    .inboxId(config.inboxId())
                    .heartbeat(Duration.ofSeconds(15))
                    .deleteDataOnClose(false)
                    .build());
            server.client().ensureInbox(config.inboxId());

            InboxModels.IssuedCredentialView issued = server.client().issueCredential(
                    config.inboxId(),
                    new InboxModels.CredentialIssueRequest(
                            PROGRAM_CREDENTIAL_LABEL,
                            PROGRAM_SCOPES,
                            Set.of(),
                            null));
            if (issued == null || issued.token() == null || issued.token().isBlank()) {
                throw new IllegalStateException("Inbox program MCP token 签发结果缺少 token");
            }
            TokenStore.writeAtomic(programTokenPath, issued.token());
            verifyOwnerOnlyFile(programTokenPath, "program MCP token");

            InboxModels.BindingDescriptor descriptor = server.client().issueBinding(
                    config.inboxId(),
                    config.bindingLabel(),
                    config.channels(),
                    wakeTokenPath,
                    Duration.ofHours(config.bindingTtlHours()));
            if (descriptor == null) {
                throw new IllegalStateException("Inbox wake binding descriptor 签发结果为空");
            }
            verifyOwnerOnlyFile(wakeTokenPath, "DSH wake token");

            String descriptorJson = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(descriptor);
            verifyDescriptorShape(descriptorJson);
            TokenStore.writeAtomic(descriptorPath, descriptorJson);

            InboxClient programClient = InboxClient.forTokenFile(
                    server.apiUrl(), programTokenPath);
            bridge = InboxNotificationBridge.forClient(
                    ticketService, postService, programClient, config.inboxId(),
                    config.channels(), Duration.ofMillis(config.pollIntervalMillis()));
            List<AgentTool> tools = new ArrayList<>(
                    new InboxClientMcpTools(programClient, config.inboxId()).all());
            tools.add(new InboxBindingDescriptorTool(descriptor, descriptorPath));
            return new InboxHostRuntime(server, programClient, descriptor, descriptorPath,
                    programTokenPath, wakeTokenPath, bridge, tools);
        } catch (IOException | RuntimeException failure) {
            if (bridge != null) {
                bridge.close();
            }
            if (server != null) {
                server.close();
            }
            throw failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Inbox embedded server 启动失败", failure);
        }
    }

    /** 启动宿主通知桥；重复调用幂等。 */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("InboxHostRuntime 已关闭，不能重新启动");
        }
        if (started.compareAndSet(false, true)) {
            notificationBridge.start();
        }
    }

    /** 立即执行一次通知扫描；生产调度之外只用于初始化/诊断/测试。 */
    public int scanOnce() {
        return notificationBridge.scanOnce();
    }

    /** 当前项目控制 MCP 注册的 11 个 inbox_* 工具 + descriptor 工具。 */
    public List<AgentTool> mcpTools() {
        return mcpTools;
    }

    public InboxModels.BindingDescriptor descriptor() {
        return descriptor;
    }

    public Path descriptorPath() {
        return descriptorPath;
    }

    public Path dataDir() {
        return server.dataDir();
    }

    /** 仅用于本机诊断/测试校验 0600；调用方不得写日志或放入 MCP 结果。 */
    public Path programTokenPath() {
        return programTokenPath;
    }

    /** 仅用于本机诊断/测试校验 0600；调用方不得写日志或放入 MCP 结果。 */
    public Path wakeTokenPath() {
        return wakeTokenPath;
    }

    /** 程序自有 InboxClient；raw token 仍只从 0600 文件读取。 */
    public InboxClient programClient() {
        return programClient;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        notificationBridge.close();
        server.close();
    }

    private static void verifyOwnerOnlyFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(label + " 文件不存在: " + path);
        }
        try {
            if (!Files.getFileStore(path)
                    .supportsFileAttributeView(PosixFileAttributeView.class)) {
                return;
            }
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path);
            Set<PosixFilePermission> expected = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (!actual.equals(expected)) {
                throw new IllegalStateException(label + " 文件权限不是 0600: " + path);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(label + " 文件权限校验失败: " + path, failure);
        }
    }

    private static void verifyDescriptorShape(String descriptorJson) throws IOException {
        JsonNode root = JSON.readTree(descriptorJson);
        Set<String> actual = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(DESCRIPTOR_FIELDS)) {
            throw new IllegalStateException(
                    "BindingDescriptor 字段不符合固定五字段契约: " + actual);
        }
    }
}
