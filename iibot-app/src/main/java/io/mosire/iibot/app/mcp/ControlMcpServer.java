package io.mosire.iibot.app.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.records.RecordQueryService;
import io.mosire.iibot.app.runtime.ControlPlane;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.decision.ActionBatchExecutor;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.agent.AdjudicationService;
import io.mosire.iibot.taskflow.agent.GmAideAgent;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.ticket.TicketActionPromotionService;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStore;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocs;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import io.mosire.iibot.taskflow.control.EventReadPort;
import io.mosire.iibot.taskflow.control.ExternalControlToolSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带 Bearer 鉴权的控制 MCP 传输层。
 *
 * <p>本类只做“鉴权 + 透明代理 + 生命周期”，不修改
 * {@link ExternalControlToolSet} 的任何工具逻辑。内部 MCP 由 AgentLib 的
 * {@link AgentToMcpServer#startHttp(String, int, String, ToolRegistry, String, String, ToolContext,
 * ToolCallAuthorizer)} 绑定在 {@code 127.0.0.1:0} 的 {@code /internal/mcp}；外部 JDK
 * {@link HttpServer} 只绑定回环地址、只接受精确配置路径，并在每个请求上校验
 * {@code Authorization: Bearer <token>}。校验通过的请求再透明转发到内部 MCP。</p>
 *
 * <p><b>安全边界</b>：外部面的 token 不转发给内部面，内部面也不直接暴露；非回环 host
 * 在启动前硬拒绝。token 不写入日志、异常、事件或本对象的 {@code toString()}。</p>
 */
public final class ControlMcpServer implements ControlPlane {

    private static final Logger LOG = LoggerFactory.getLogger(ControlMcpServer.class);

    private static final String INTERNAL_HOST = "127.0.0.1";
    private static final String INTERNAL_PATH = "/internal/mcp";
    private static final String SERVER_NAME = "iibot-control";
    private static final String SERVER_VERSION = "0.1.0";
    private static final String TASKFLOW_NAMESPACE = "taskflow";
    private static final String SUPERVISOR_ROLE = "supervisor";

    /** 完整控制面启用时由 tf_control_* 取代的旧 identity/world 写工具。 */
    private static final Set<String> CONTROL_REPLACED_LEGACY_TOOLS = Set.of(
            ExternalControlToolSet.TF_REGISTER_PLAYER,
            ExternalControlToolSet.TF_REGISTER_DIRECTOR,
            ExternalControlToolSet.TF_LOAD_WORLD_FACTS);

    private static final Set<String> FORWARD_REQUEST_HEADERS = Set.of(
            HttpHeaders.ACCEPT.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.CONTENT_TYPE.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.MCP_SESSION_ID.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.PROTOCOL_VERSION.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.LAST_EVENT_ID.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.CACHE_CONTROL.toLowerCase(java.util.Locale.ROOT));

    private static final Set<String> FORWARD_RESPONSE_HEADERS = Set.of(
            HttpHeaders.CONTENT_TYPE.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.CACHE_CONTROL.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.MCP_SESSION_ID.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.PROTOCOL_VERSION.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.LAST_EVENT_ID.toLowerCase(java.util.Locale.ROOT),
            "vary",
            "expires",
            "pragma",
            "www-authenticate");

    private final TaskflowEngine engine;
    private final EventReadPort eventReadPort;
    private final Actor supervisor;
    private final ControlMcpConfig config;
    private final Clock clock;
    private final SecretProvider secretProvider;
    private final String injectedBearerToken;
    private final AdjudicationService adjudicationService;
    private final GmAideAgent gmAideAgent;
    private final SecretaryWorkspaceStore secretaryWorkspaceStore;
    private final SecretarySessionRegistry secretarySessions;
    private final PostService postService;
    private final SharedSkillStore sharedSkillStore;
    private final ComplaintService complaintService;
    private final TicketService ticketService;
    private final TicketActionPromotionService ticketActionPromotionService;
    private final ActionPoolService actionPoolService;
    private final ActionPackageService actionPackageService;
    private final ActionBatchExecutor actionBatchExecutor;
    private final PublicationOrchestrator publicationOrchestrator;
    private final RecordQueryService recordQueryService;
    private final ControlBootstrapService controlBootstrapService;
    private final GmPrivateDocs gmPrivateDocs;
    private final Runnable afterMutation;
    private final HttpClient upstreamClient;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile byte[] expectedAuthorization;
    private volatile boolean started;
    private volatile int boundPort = -1;
    private volatile int internalPort = -1;
    private HttpServer frontServer;
    private AgentToMcpServer internalServer;
    private ExecutorService frontExecutor;

    private ControlMcpServer(Builder builder) {
        this.engine = builder.engine;
        this.eventReadPort = builder.eventReadPort;
        this.supervisor = builder.supervisor;
        this.config = builder.config;
        this.clock = builder.clock;
        this.secretProvider = builder.secretProvider;
        this.injectedBearerToken = builder.injectedBearerToken;
        this.adjudicationService = builder.adjudicationService;
        this.gmAideAgent = builder.gmAideAgent;
        this.secretaryWorkspaceStore = builder.secretaryWorkspaceStore;
        this.secretarySessions = builder.secretarySessions;
        this.postService = builder.postService;
        this.sharedSkillStore = builder.sharedSkillStore;
        this.complaintService = builder.complaintService;
        this.ticketService = builder.ticketService;
        this.ticketActionPromotionService = builder.ticketActionPromotionService;
        this.actionPoolService = builder.actionPoolService;
        this.actionPackageService = builder.actionPackageService;
        this.actionBatchExecutor = builder.actionBatchExecutor;
        this.publicationOrchestrator = builder.publicationOrchestrator;
        this.recordQueryService = builder.recordQueryService;
        this.controlBootstrapService = builder.controlBootstrapService;
        this.gmPrivateDocs = builder.gmPrivateDocs;
        this.afterMutation = builder.afterMutation;
        this.upstreamClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 创建 builder；所有依赖均为显式注入，不使用全局单例。 */
    public static Builder builder(
            TaskflowEngine engine,
            EventReadPort eventReadPort,
            Actor supervisor,
            ControlMcpConfig config) {
        return new Builder(engine, eventReadPort, supervisor, config);
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("控制 MCP 已关闭，不能重新启动");
            }
            if (started) {
                return;
            }
            if (!config.enabled()) {
                started = true;
                return;
            }

            // 非回环配置必须在任何 bind 之前拒绝；不是告警后继续。
            if (!isLoopbackHost(config.host())) {
                throw new IllegalStateException(
                        "控制 MCP 拒绝非回环 host: " + config.host() + "（只允许回环地址）");
            }

            String token = resolveBearerToken();
            byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);

            AgentToMcpServer internal = null;
            HttpServer front = null;
            ExecutorService executor = null;
            boolean frontStarted = false;
            try {
                ExternalControlToolSet toolSet = ExternalControlToolSet.builder(engine, supervisor)
                        .eventReadPort(eventReadPort)
                        .clock(clock)
                        .build();
                ToolRegistry registry = new ToolRegistry();
                for (var tool : toolSet.tools()) {
                    if (controlBootstrapService != null
                            && CONTROL_REPLACED_LEGACY_TOOLS.contains(tool.name())) {
                        continue;
                    }
                    registry.register(tool);
                }
                if (adjudicationService != null) {
                    registry.register(new RunAdjudicationAgentTool(
                            engine, adjudicationService, supervisor));
                }
                if (gmAideAgent != null) {
                    registry.register(new GmAideChatTool(gmAideAgent));
                }
                if (hasSecretaryTools()) {
                    for (var tool : GmSecretaryMcpTools.all(
                            secretaryWorkspaceStore, secretarySessions)) {
                        registry.register(tool);
                    }
                }
                if (postService != null) {
                    for (var tool : GmPostMcpTools.all(postService, supervisor)) {
                        registry.register(tool);
                    }
                }
                if (publicationOrchestrator != null) {
                    for (var tool : GmPublicationMcpTools.all(
                            publicationOrchestrator, supervisor)) {
                        registry.register(tool);
                    }
                }
                if (recordQueryService != null) {
                    for (var tool : GmRecordMcpTools.all(recordQueryService)) {
                        registry.register(tool);
                    }
                }
                if (sharedSkillStore != null) {
                    for (var tool : GmSharedSkillMcpTools.all(sharedSkillStore)) {
                        registry.register(tool);
                    }
                }
                if (ticketService != null) {
                    // 完整 Ticket 装配取代旧 complaint MCP 三工具，避免双重状态主路径。
                    List<io.mosire.agentlib.tool.AgentTool> ticketTools =
                            ticketActionPromotionService == null
                                    ? GmTicketMcpTools.all(ticketService, supervisor)
                                    : GmTicketMcpTools.all(ticketService,
                                            ticketActionPromotionService, supervisor);
                    for (var tool : ticketTools) {
                        registry.register(tool);
                    }
                } else if (complaintService != null) {
                    for (var tool : GmComplaintMcpTools.all(complaintService, supervisor)) {
                        registry.register(tool);
                    }
                }
                if (actionPoolService != null) {
                    for (var tool : GmActionPoolMcpTools.all(
                            actionPoolService, actionPackageService, supervisor)) {
                        registry.register(tool);
                    }
                }
                if (actionPackageService != null) {
                    for (var tool : GmDecisionPackageMcpTools.all(
                            actionPackageService, actionBatchExecutor, supervisor)) {
                        registry.register(tool);
                    }
                    for (var tool : GmActionBatchMcpTools.all(actionPackageService)) {
                        registry.register(tool);
                    }
                }
                if (controlBootstrapService != null) {
                    for (var tool : ControlBootstrapMcpTools.all(controlBootstrapService)) {
                        registry.register(tool);
                    }
                }
                if (gmPrivateDocs != null) {
                    for (var tool : GmPrivateDocsMcpTools.all(gmPrivateDocs)) {
                        registry.register(tool);
                    }
                }

                internal = AgentToMcpServer.startHttp(
                        INTERNAL_HOST,
                        0,
                        INTERNAL_PATH,
                        registry,
                        SERVER_NAME,
                        SERVER_VERSION,
                        controlCallerContext(),
                        ToolCallAuthorizer.standard());
                int internalBound = internal.boundPort();
                if (internalBound <= 0) {
                    throw new IllegalStateException("内部 MCP 未返回有效绑定端口");
                }

                executor = Executors.newVirtualThreadPerTaskExecutor();
                front = HttpServer.create(
                        new InetSocketAddress(config.host(), config.port()), 0);
                front.setExecutor(executor);
                front.createContext("/", this::handleProxy);
                front.start();
                frontStarted = true;

                this.internalServer = internal;
                this.frontServer = front;
                this.frontExecutor = executor;
                this.internalPort = internalBound;
                this.boundPort = front.getAddress().getPort();
                this.expectedAuthorization = expected;
                this.started = true;
            } catch (IOException | RuntimeException failure) {
                if (front != null && frontStarted) {
                    try {
                        front.stop(0);
                    } catch (RuntimeException ignored) {
                        // 启动失败的收尾不掩盖原异常
                    }
                }
                if (internal != null) {
                    try {
                        internal.close();
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
                if (executor != null) {
                    executor.shutdownNow();
                }
                throw new IllegalStateException(
                        "控制 MCP 启动失败（host=" + config.host()
                                + ", port=" + config.port() + ", path=" + config.path() + "）",
                        failure);
            }
        }
    }

    /**
     * 实际绑定的外部端口；仅用于测试/装配后的端口回读。
     *
     * @throws IllegalStateException 尚未启动
     */
    public int boundPort() {
        if (!started || boundPort <= 0) {
            throw new IllegalStateException("控制 MCP 尚未启动");
        }
        return boundPort;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // 顺序：先停外部接收 → 关 front HttpServer → 关内部 MCP → 关 executor。
            HttpServer front = this.frontServer;
            this.frontServer = null;
            if (front != null) {
                try {
                    front.stop(0);
                } catch (RuntimeException ignored) {
                    // 幂等关闭：单个资源失败不阻断其余资源释放
                }
            }

            AgentToMcpServer internal = this.internalServer;
            this.internalServer = null;
            if (internal != null) {
                try {
                    internal.close();
                } catch (RuntimeException ignored) {
                    // 同上
                }
            }

            ExecutorService executor = this.frontExecutor;
            this.frontExecutor = null;
            if (executor != null) {
                executor.shutdownNow();
            }

            byte[] expected = this.expectedAuthorization;
            if (expected != null) {
                Arrays.fill(expected, (byte) 0);
                this.expectedAuthorization = null;
            }
            started = false;
        }
    }

    @Override
    public String toString() {
        return "ControlMcpServer{enabled=" + config.enabled()
                + ", host='" + config.host()
                + "', path='" + config.path()
                + "', bearerConfigured=" + (expectedAuthorization != null)
                + ", started=" + started
                + '}';
    }

    // ------------------------------------------------------------------
    // 外部面：鉴权 + 精确路径
    // ------------------------------------------------------------------

    private void handleProxy(HttpExchange exchange) throws IOException {
        try {
            if (!config.path().equals(exchange.getRequestURI().getPath())) {
                sendText(exchange, 404, "未知路径");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate",
                        "Bearer realm=\"iibot-control\"");
                sendText(exchange, 401, "未授权");
                return;
            }

            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"POST".equals(method) && !"DELETE".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            proxy(exchange, method);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        byte[] expected = expectedAuthorization;
        if (expected == null) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        // 逐字节常数时间比较；不做 trim/忽略大小写/前后缀容错。
        return MessageDigest.isEqual(expected, actual);
    }

    private void proxy(HttpExchange exchange, String method) throws IOException {
        // 请求体先做有界读取：上限内才转发。响应体则必须流式转发（POST SSE / GET 监听流）。
        byte[] requestBody = null;
        if ("POST".equals(method)) {
            requestBody = readBoundedRequestBody(exchange);
            if (requestBody == null) {
                return; // readBoundedRequestBody 已发送 400/413
            }
        }

        URI upstreamUri = internalUri(exchange.getRequestURI().getRawQuery());
        HttpRequest.Builder request = HttpRequest.newBuilder(upstreamUri);
        copyRequestHeaders(exchange.getRequestHeaders(), request);
        if ("POST".equals(method)) {
            request.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<InputStream> response;
        try {
            response = upstreamClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendText(exchange, 502, "内部 MCP 请求被中断");
            return;
        } catch (IOException failure) {
            sendText(exchange, 502, "内部 MCP 不可达");
            return;
        }

        int status = response.statusCode();
        copyResponseHeaders(response.headers(), exchange.getResponseHeaders());
        if ("HEAD".equals(method) || status < 200 || status == 204 || status == 304) {
            try {
                response.body().close();
            } finally {
                exchange.sendResponseHeaders(status, -1);
            }
            runAfterMutationIfSuccessful(method, status);
            return;
        }

        long responseLength = response.headers()
                .firstValueAsLong(HttpHeaders.CONTENT_LENGTH)
                .orElse(-1L);
        exchange.sendResponseHeaders(status, responseLength >= 0 ? responseLength : 0);

        // 不能先全量读入：POST 的 SSE 与 GET 监听流都必须边到边转发。
        try (InputStream upstream = response.body();
             OutputStream downstream = exchange.getResponseBody()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = upstream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                downstream.write(buffer, 0, read);
                downstream.flush();
            }
        } catch (IOException clientOrUpstreamFailure) {
            // 客户端断开或上游关闭时，try-with-resources 已关闭上游；
            // 不再尝试写响应，也绝不把 token/内部错误回显给客户端。
            return;
        }
        // 只有 POST 成功且响应体完整转发后才触发宿主变更后钩子。hook 自身失败
        // 不能改写已经发出的 MCP 响应，更不能吞掉工具自己的 ToolResult 错误。
        runAfterMutationIfSuccessful(method, status);
    }

    private void runAfterMutationIfSuccessful(String method, int status) {
        if (!"POST".equals(method) || status < 200 || status >= 300 || afterMutation == null) {
            return;
        }
        try {
            afterMutation.run();
        } catch (RuntimeException failure) {
            // 只记录异常类型，不回显可能包含内部数据/凭据的异常消息。
            LOG.warn("控制 MCP afterMutation 回调失败 type={}",
                    failure.getClass().getSimpleName());
        }
    }

    private URI internalUri(String rawQuery) {
        String query = rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery;
        return URI.create("http://" + INTERNAL_HOST + ':' + internalPort + INTERNAL_PATH + query);
    }

    private static void copyRequestHeaders(Headers incoming, HttpRequest.Builder request) {
        incoming.forEach((name, values) -> {
            if (!FORWARD_REQUEST_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                request.header(name, value);
            }
        });
    }

    private static void copyResponseHeaders(java.net.http.HttpHeaders incoming, Headers outgoing) {
        incoming.map().forEach((name, values) -> {
            if (!FORWARD_RESPONSE_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                outgoing.add(name, value);
            }
        });
    }

    /**
     * 读取 POST 请求体，最多 {@link ControlMcpConfig#maxRequestBytes()} 字节。
     *
     * @return 请求体；超限/非法时返回 {@code null}（响应已由本方法发送）
     */
    private byte[] readBoundedRequestBody(HttpExchange exchange) throws IOException {
        String rawLength = exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        if (rawLength != null && !rawLength.isBlank()) {
            try {
                long declared = Long.parseLong(rawLength.trim());
                if (declared < 0) {
                    sendText(exchange, 400, "Content-Length 非法");
                    return null;
                }
                if (declared > config.maxRequestBytes()) {
                    sendText(exchange, 413, "请求体过大");
                    return null;
                }
            } catch (NumberFormatException invalid) {
                sendText(exchange, 400, "Content-Length 非法");
                return null;
            }
        }

        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        try (InputStream input = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > config.maxRequestBytes()) {
                    sendText(exchange, 413, "请求体过大");
                    return null;
                }
                collected.write(buffer, 0, read);
            }
        }
        return collected.toByteArray();
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------
    // 内部 MCP 工具面：固定工具 + 可选 Agent/秘书工作区工具、精确最小权限（不允许 * / SYSTEM）
    // ------------------------------------------------------------------

    private ToolContext controlCallerContext() {
        Set<String> allTools = new LinkedHashSet<>();
        allTools.addAll(ExternalControlToolSet.READ_TOOL_NAMES);
        allTools.addAll(ExternalControlToolSet.WRITE_TOOL_NAMES);
        if (adjudicationService != null) {
            allTools.add(RunAdjudicationAgentTool.NAME);
        }
        if (gmAideAgent != null) {
            allTools.add(GmAideChatTool.NAME);
        }
        if (hasSecretaryTools()) {
            allTools.addAll(GmSecretaryMcpTools.NAMES);
        }
        if (postService != null) {
            allTools.addAll(GmPostMcpTools.NAMES);
        }
        if (publicationOrchestrator != null) {
            allTools.addAll(GmPublicationMcpTools.NAMES);
        }
        if (recordQueryService != null) {
            allTools.addAll(GmRecordMcpTools.NAMES);
        }
        if (sharedSkillStore != null) {
            allTools.addAll(GmSharedSkillMcpTools.NAMES);
        }
        if (ticketService != null) {
            allTools.addAll(GmTicketMcpTools.BASE_NAMES);
            if (ticketActionPromotionService != null) {
                allTools.add(GmTicketPromoteActionTool.NAME);
            }
        } else if (complaintService != null) {
            allTools.addAll(GmComplaintMcpTools.NAMES);
        }
        if (actionPoolService != null) {
            allTools.addAll(GmActionPoolMcpTools.NAMES);
        }
        if (actionPackageService != null) {
            allTools.addAll(GmDecisionPackageMcpTools.NAMES);
            allTools.addAll(GmActionBatchMcpTools.NAMES);
        }
        if (controlBootstrapService != null) {
            // 完整控制面不能同时暴露旧 identity/world 写路径；allowlist 必须与真实
            // registry 保持一致，旧三工具既不注册也不授权。
            allTools.removeAll(CONTROL_REPLACED_LEGACY_TOOLS);
            allTools.addAll(ControlBootstrapMcpTools.NAMES);
        }
        if (gmPrivateDocs != null) {
            allTools.addAll(GmPrivateDocsMcpTools.NAMES);
        }

        Map<String, ResourceScope> scopes = new java.util.LinkedHashMap<>();
        scopes.put(TASKFLOW_NAMESPACE, ResourceScope.unlimited());
        if (hasSecretaryTools()) {
            scopes.put(SecretaryWorkspaceStore.RESOURCE_NAMESPACE,
                    ResourceScope.of("workspace"));
        }
        if (postService != null) {
            scopes.put(PostStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(PostStore.RESOURCE_NAMESPACE));
        }
        if (publicationOrchestrator != null) {
            scopes.put(GmPublicationMcpTools.RESOURCE_NAMESPACE,
                    ResourceScope.of(GmPublicationMcpTools.RESOURCE_NAMESPACE));
        }
        if (recordQueryService != null) {
            scopes.put(GmRecordMcpTools.RESOURCE_NAMESPACE,
                    ResourceScope.of(GmRecordMcpTools.RESOURCE_NAMESPACE));
        }
        if (sharedSkillStore != null) {
            scopes.put(SharedSkillStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(SharedSkillStore.RESOURCE_NAMESPACE));
        }
        if (ticketService != null) {
            scopes.put(TicketStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(TicketStore.RESOURCE_NAMESPACE));
        } else if (complaintService != null) {
            scopes.put(ComplaintStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(ComplaintStore.RESOURCE_NAMESPACE));
        }
        if (actionPoolService != null || ticketActionPromotionService != null) {
            scopes.put(ActionPoolStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(ActionPoolStore.RESOURCE_NAMESPACE));
        }
        if (actionPackageService != null) {
            scopes.put(GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE,
                    ResourceScope.of(GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE));
            scopes.put(GmDecisionToolSupport.ACTION_BATCH_NAMESPACE,
                    ResourceScope.of(GmDecisionToolSupport.ACTION_BATCH_NAMESPACE));
        }
        if (gmPrivateDocs != null) {
            scopes.put(GmPrivateDocs.RESOURCE_NAMESPACE,
                    ResourceScope.of(GmPrivateDocs.RESOURCE_NAMESPACE));
        }
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allTools.toArray(String[]::new))
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .resourceScopes(new ResourceScopeMap(scopes))
                .roles(RoleSet.of(SUPERVISOR_ROLE))
                .build();
        return ToolContext.of(AccessToken.DEFAULT, permissions, AgentIdentity.external());
    }

    private boolean hasSecretaryTools() {
        return secretaryWorkspaceStore != null && secretarySessions != null;
    }

    private String resolveBearerToken() {
        String token = injectedBearerToken;
        if (token == null) {
            if (config.bearerTokenEnv().isBlank()) {
                throw new IllegalStateException(
                        "控制 MCP Bearer token 未配置：bearerTokenEnv 为空且未显式注入 token");
            }
            token = secretProvider.lookup(config.bearerTokenEnv())
                    .orElseThrow(() -> new IllegalStateException(
                            "控制 MCP Bearer token 未配置：环境变量 "
                                    + config.bearerTokenEnv() + " 为空"));
        }
        if (token.isBlank()) {
            throw new IllegalStateException("控制 MCP Bearer token 不能为空");
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalStateException("控制 MCP Bearer token 含空白或控制字符");
            }
        }
        return token;
    }

    private static boolean isLoopbackHost(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException unresolved) {
            return false;
        }
    }

    /** 可测试/可扩展的构造器；token 明文只作为 builder 的测试注入点存在。 */
    public static final class Builder {
        private final TaskflowEngine engine;
        private final EventReadPort eventReadPort;
        private final Actor supervisor;
        private final ControlMcpConfig config;
        private SecretProvider secretProvider = SecretProvider.environment();
        private String injectedBearerToken;
        private Clock clock = Clock.systemUTC();
        private AdjudicationService adjudicationService;
        private GmAideAgent gmAideAgent;
        private SecretaryWorkspaceStore secretaryWorkspaceStore;
        private SecretarySessionRegistry secretarySessions;
        private PostService postService;
        private SharedSkillStore sharedSkillStore;
        private ComplaintService complaintService;
        private TicketService ticketService;
        private TicketActionPromotionService ticketActionPromotionService;
        private ActionPoolService actionPoolService;
        private ActionPackageService actionPackageService;
        private ActionBatchExecutor actionBatchExecutor;
        private PublicationOrchestrator publicationOrchestrator;
        private RecordQueryService recordQueryService;
        private ControlBootstrapService controlBootstrapService;
        private GmPrivateDocs gmPrivateDocs;
        private Runnable afterMutation;

        private Builder(
                TaskflowEngine engine,
                EventReadPort eventReadPort,
                Actor supervisor,
                ControlMcpConfig config) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.eventReadPort = Objects.requireNonNull(eventReadPort, "eventReadPort");
            this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
            this.config = Objects.requireNonNull(config, "config");
        }

        /** 生产形态：从 {@link SecretProvider} 按配置中的环境变量名解析 token。 */
        public Builder secretProvider(SecretProvider secretProvider) {
            this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider");
            return this;
        }

        /** 测试/显式注入点；生产配置不应把 token 本身写进配置文件。 */
        public Builder bearerToken(String bearerToken) {
            this.injectedBearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
            return this;
        }

        /** 与工具集/引擎共用的时钟；缺省 UTC。 */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * 可选注入中央推演服务。只有注入后才注册 {@link RunAdjudicationAgentTool}；
         * 未注入时工具面保持原有 18 个工具。
         */
        public Builder adjudicationService(AdjudicationService adjudicationService) {
            this.adjudicationService =
                    Objects.requireNonNull(adjudicationService, "adjudicationService");
            return this;
        }

        /**
         * 可选注入 GM 推演秘书。注入后工具面增加 {@link GmAideChatTool}；未注入时
         * 工具面与既有行为完全一致。
         */
        public Builder gmAideAgent(GmAideAgent gmAideAgent) {
            this.gmAideAgent = Objects.requireNonNull(gmAideAgent, "gmAideAgent");
            return this;
        }

        /**
         * 可选注入每玩家秘书工作区。必须与 {@link #secretarySessions} 同时注入，才会注册
         * GM 秘书工作区/对话四个工具；只注入其中一个时工具面保持不变。
         */
        public Builder secretaryWorkspaceStore(SecretaryWorkspaceStore secretaryWorkspaceStore) {
            this.secretaryWorkspaceStore = Objects.requireNonNull(
                    secretaryWorkspaceStore, "secretaryWorkspaceStore");
            return this;
        }

        /**
         * 可选注入秘书会话注册表。必须与 {@link #secretaryWorkspaceStore} 同时注入，才会
         * 注册 GM 秘书工作区/对话四个工具。
         */
        public Builder secretarySessions(SecretarySessionRegistry secretarySessions) {
            this.secretarySessions = Objects.requireNonNull(
                    secretarySessions, "secretarySessions");
            return this;
        }

        /**
         * 可选注入推文服务。注入后额外注册四个 GM 审批/发布工具；未注入时工具面保持
         * 与既有行为完全一致。
         */
        public Builder postService(PostService postService) {
            this.postService = Objects.requireNonNull(postService, "postService");
            return this;
        }

        /**
         * 可选注入全局共享 Skill 区。注入后额外注册三个 supervisor 只读/写入工具；
         * 未注入时工具面保持不变。
         */
        public Builder sharedSkillStore(SharedSkillStore sharedSkillStore) {
            this.sharedSkillStore = Objects.requireNonNull(sharedSkillStore, "sharedSkillStore");
            return this;
        }

        /**
         * 可选注入旧投诉服务。未注入 TicketService 时注册三个 supervisor 投诉工具；
         * 注入 TicketService 后旧三工具隐藏。
         */
        public Builder complaintService(ComplaintService complaintService) {
            this.complaintService = Objects.requireNonNull(complaintService, "complaintService");
            return this;
        }

        /**
         * 可选注入统一 Ticket 服务。注入后注册 8 个 tf_ticket_* 工具并隐藏旧
         * tf_complaint_* 三工具；未注入时旧 complaint 装配保持兼容。
         */
        public Builder ticketService(TicketService ticketService) {
            this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
            return this;
        }

        /**
         * 可选注入 ACTION_DRAFT 晋升编排。仅在 TicketService 同时注入时注册
         * tf_ticket_promote_action；完整控制面工具数变为 61。
         */
        public Builder ticketActionPromotionService(
                TicketActionPromotionService ticketActionPromotionService) {
            this.ticketActionPromotionService = Objects.requireNonNull(
                    ticketActionPromotionService, "ticketActionPromotionService");
            return this;
        }

        /** 可选注入行动池；注入后注册三个 supervisor 行动池工具。 */
        public Builder actionPoolService(ActionPoolService actionPoolService) {
            this.actionPoolService = Objects.requireNonNull(
                    actionPoolService, "actionPoolService");
            return this;
        }

        /** 可选注入裁定包服务；注入后注册九个包工具与两个批次工具。 */
        public Builder actionPackageService(ActionPackageService actionPackageService) {
            this.actionPackageService = Objects.requireNonNull(
                    actionPackageService, "actionPackageService");
            return this;
        }

        /** 可选的包 commit 执行器；未注入时 commit 工具返回稳定不可用错误。 */
        public Builder actionBatchExecutor(ActionBatchExecutor actionBatchExecutor) {
            this.actionBatchExecutor = Objects.requireNonNull(
                    actionBatchExecutor, "actionBatchExecutor");
            return this;
        }

        /** 可选注入发布编排；注入后注册 tf_publication_generate（工具面 62）。 */
        public Builder publicationOrchestrator(PublicationOrchestrator publicationOrchestrator) {
            this.publicationOrchestrator = Objects.requireNonNull(
                    publicationOrchestrator, "publicationOrchestrator");
            return this;
        }

        /** 可选注入 Markdown 回合记录查询服务；注入后注册三个 tf_record_* 工具。 */
        public Builder recordQueryService(RecordQueryService recordQueryService) {
            this.recordQueryService = Objects.requireNonNull(
                    recordQueryService, "recordQueryService");
            return this;
        }

        /**
         * 可选注入运行期控制初始化服务。只有注入后才注册六个 tf_control_* 工具；
         * 未注入时旧工具面与既有测试完全不变。
         */
        public Builder controlBootstrapService(ControlBootstrapService controlBootstrapService) {
            this.controlBootstrapService = Objects.requireNonNull(
                    controlBootstrapService, "controlBootstrapService");
            return this;
        }

        /** {@link #controlBootstrapService(ControlBootstrapService)} 的短别名。 */
        public Builder controlBootstrap(ControlBootstrapService controlBootstrapService) {
            return controlBootstrapService(controlBootstrapService);
        }

        /**
         * 可选注入 GM 私有文档区。只有注入后才注册 tf_gm_doc_* 与
         * tf_control_load_gm_doc 四个工具；未注入时工具面不变。
         */
        public Builder gmPrivateDocs(GmPrivateDocs gmPrivateDocs) {
            this.gmPrivateDocs = Objects.requireNonNull(gmPrivateDocs, "gmPrivateDocs");
            return this;
        }

        /** {@link #gmPrivateDocs(GmPrivateDocs)} 的短别名。 */
        public Builder gmPrivateDocsStore(GmPrivateDocs gmPrivateDocs) {
            return gmPrivateDocs(gmPrivateDocs);
        }

        /**
         * 可选宿主变更后钩子。仅在成功代理的 POST 响应完整转发后调用；异常被隔离并
         * 只记录异常类型，不影响已返回的 MCP 响应。
         */
        public Builder afterMutation(Runnable afterMutation) {
            this.afterMutation = Objects.requireNonNull(afterMutation, "afterMutation");
            return this;
        }

        public ControlMcpServer build() {
            return new ControlMcpServer(this);
        }
    }
}
