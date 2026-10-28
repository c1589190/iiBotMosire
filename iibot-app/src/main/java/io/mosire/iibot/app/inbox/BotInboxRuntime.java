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
import io.mosire.inbox.server.ServerConfig;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Bot-first 的通用 Inbox embedded runtime：只签发 MCP/wake 凭据、提供 Inbox 工具和
 * digest 端口，不绑定 Ticket/Post 等旧文游服务。
 */
public final class BotInboxRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BotInboxRuntime.class);

    public static final String PROGRAM_TOKEN_FILE = "program-mcp.token";
    public static final String WAKE_TOKEN_FILE = "dsh-wake.token";
    public static final String DESCRIPTOR_FILE = "binding-descriptor.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROGRAM_CREDENTIAL_LABEL = "iibot-bot-program-mcp";
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
    private final List<AgentTool> mcpTools;
    private final InboxClientDigestPort digestPort;
    private final AtomicBoolean closed = new AtomicBoolean();

    private BotInboxRuntime(EmbeddedInboxServer server, InboxClient programClient,
                            InboxModels.BindingDescriptor descriptor,
                            Path descriptorPath, Path programTokenPath, Path wakeTokenPath,
                            List<AgentTool> mcpTools, InboxClientDigestPort digestPort) {
        this.server = server;
        this.programClient = programClient;
        this.descriptor = descriptor;
        this.descriptorPath = descriptorPath;
        this.programTokenPath = programTokenPath;
        this.wakeTokenPath = wakeTokenPath;
        this.mcpTools = List.copyOf(mcpTools);
        this.digestPort = digestPort;
    }

    /** 旧调用的默认网络参数：随机回环端口、/mcp、15 秒 heartbeat、5 秒 sweeper。 */
    public static BotInboxRuntime start(Path dataDir, String inboxId, String bindingLabel,
                                        Set<String> channels, long bindingTtlHours)
            throws IOException {
        return start(BotInboxRuntimeOptions.defaults(dataDir, inboxId, bindingLabel,
                channels, bindingTtlHours));
    }

    public static BotInboxRuntime start(BotInboxRuntimeOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("inbox-start"))) {
            return startScoped(options);
        }
    }

    private static BotInboxRuntime startScoped(BotInboxRuntimeOptions options)
            throws IOException {
        Path absolute = options.dataDir();
        String inboxId = options.inboxId();
        Path programTokenPath = absolute.resolve(PROGRAM_TOKEN_FILE);
        Path wakeTokenPath = absolute.resolve(WAKE_TOKEN_FILE);
        Path descriptorPath = absolute.resolve(DESCRIPTOR_FILE);
        EmbeddedInboxServer server = null;
        OpsLog.info("event=inbox_embedded_starting inboxId={} dataDir={} "
                        + "apiHost={} apiPort={} mcpHost={} mcpPort={} mcpPath={}",
                inboxId, absolute, options.apiHost(), options.apiPort(), options.mcpHost(),
                options.mcpPort(), options.mcpPath());
        try {
            Files.createDirectories(absolute);
            LOG.debug("event=inbox_client_action action=create_data_dir inboxId={}",
                    inboxId);
            server = EmbeddedInboxServer.start(EmbeddedInboxOptions.builder()
                    .dataDir(absolute)
                    .inboxId(inboxId)
                    .apiHost(options.apiHost())
                    .apiPort(options.apiPort())
                    .mcpHost(options.mcpHost())
                    .mcpPort(options.mcpPort())
                    .mcpPath(options.mcpPath())
                    .heartbeat(options.heartbeat())
                    .claimSweepInterval(options.claimSweepInterval())
                    .deleteDataOnClose(false)
                    .build());
            LOG.debug("event=inbox_client_action action=ensure_inbox inboxId={}", inboxId);
            server.client().ensureInbox(inboxId);
            LOG.debug("event=inbox_client_action action=issue_credential inboxId={} "
                            + "scopeCount={}", inboxId, PROGRAM_SCOPES.size());
            InboxModels.IssuedCredentialView issued = server.client().issueCredential(
                    inboxId,
                    new InboxModels.CredentialIssueRequest(
                            PROGRAM_CREDENTIAL_LABEL, PROGRAM_SCOPES, Set.of(), null));
            if (issued == null || issued.token() == null || issued.token().isBlank()) {
                throw new IllegalStateException("Inbox program MCP token 签发结果缺少 token");
            }
            TokenStore.writeAtomic(programTokenPath, issued.token());
            verifyOwnerOnlyFile(programTokenPath, "program MCP token");
            LOG.debug("event=inbox_client_action action=program_token_written inboxId={}",
                    inboxId);

            LOG.debug("event=inbox_client_action action=issue_binding inboxId={} "
                            + "channelCount={} ttlHours={}", inboxId,
                    options.channels().size(), options.bindingTtlHours());
            InboxModels.BindingDescriptor descriptor = server.client().issueBinding(
                    inboxId, options.bindingLabel(), options.channels(), wakeTokenPath,
                    Duration.ofHours(options.bindingTtlHours()));
            if (descriptor == null) {
                throw new IllegalStateException("Inbox wake binding descriptor 签发结果为空");
            }
            verifyOwnerOnlyFile(wakeTokenPath, "DSH wake token");
            String descriptorJson = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(descriptor);
            verifyDescriptorShape(descriptorJson);
            TokenStore.writeAtomic(descriptorPath, descriptorJson);
            LOG.debug("event=inbox_client_action action=descriptor_written inboxId={} "
                            + "descriptor={}", inboxId, descriptorPath);

            InboxClient programClient = InboxClient.forTokenFile(
                    server.apiUrl(), programTokenPath);
            List<AgentTool> tools = new ArrayList<>(
                    new InboxClientMcpTools(programClient, inboxId).all());
            tools.add(new InboxBindingDescriptorTool(descriptor, descriptorPath));
            InboxClientDigestPort digestPort = new InboxClientDigestPort(programClient, inboxId);
            OpsLog.info("event=inbox_embedded_started inboxId={} apiPort={} mcpPort={} "
                            + "descriptor={} tools={}",
                    inboxId, server.apiPort(), server.mcpPort(), descriptorPath, tools.size());
            return new BotInboxRuntime(server, programClient, descriptor, descriptorPath,
                    programTokenPath, wakeTokenPath, tools, digestPort);
        } catch (IOException | RuntimeException failure) {
            OpsLog.error("event=inbox_embedded_failed inboxId={} errorType={}",
                    inboxId, failure.getClass().getSimpleName());
            if (server != null) {
                server.close();
            }
            throw failure;
        }
    }

    public List<AgentTool> mcpTools() {
        return mcpTools;
    }

    public InboxClientDigestPort digestPort() {
        return digestPort;
    }

    public InboxClient programClient() {
        return programClient;
    }

    public InboxModels.BindingDescriptor descriptor() {
        return descriptor;
    }

    public Path descriptorPath() {
        return descriptorPath;
    }

    public Path programTokenPath() {
        return programTokenPath;
    }

    public Path wakeTokenPath() {
        return wakeTokenPath;
    }

    public Path dataDir() {
        return server.dataDir();
    }

    /** 请求配置（port=0 仍表示随机）；实际端口用 apiPort()/mcpPort()。 */
    public ServerConfig serverConfig() {
        return server.serverConfig();
    }

    public Duration claimSweepInterval() {
        return server.claimSweepInterval();
    }

    public int apiPort() {
        return server.apiPort();
    }

    public int mcpPort() {
        return server.mcpPort();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.close();
        LOG.debug("event=inbox_client_action action=embedded_stopped");
        OpsLog.info("event=inbox_embedded_stopped");
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
