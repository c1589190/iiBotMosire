package io.mosire.iibot.bot.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.iibot.connect.ops.OpsLog;

import java.io.ByteArrayOutputStream;
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
 * Bot-first 的回环 Bearer MCP 传输。
 *
 * <p>内部 MCP 只绑 {@code 127.0.0.1:0}；外部面按精确路径和 Bearer token 透明代理，
 * 支持 streamable HTTP 的 GET/POST/DELETE 与流式响应。非回环 host 在 bind 前硬拒绝。</p>
 */
public final class BotMcpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BotMcpServer.class);
    private static final String INTERNAL_HOST = "127.0.0.1";
    private static final String INTERNAL_PATH = "/internal/mcp";
    private static final String SUPERVISOR_ROLE = "supervisor";
    private static final int DEFAULT_MAX_REQUEST_BYTES = 4 * 1024 * 1024;

    private static final Set<String> REQUEST_HEADERS = Set.of(
            HttpHeaders.ACCEPT.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.CONTENT_TYPE.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.MCP_SESSION_ID.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.PROTOCOL_VERSION.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.LAST_EVENT_ID.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.CACHE_CONTROL.toLowerCase(java.util.Locale.ROOT));
    private static final Set<String> RESPONSE_HEADERS = Set.of(
            HttpHeaders.CONTENT_TYPE.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.CACHE_CONTROL.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.MCP_SESSION_ID.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.PROTOCOL_VERSION.toLowerCase(java.util.Locale.ROOT),
            HttpHeaders.LAST_EVENT_ID.toLowerCase(java.util.Locale.ROOT),
            "vary", "expires", "pragma", "www-authenticate");

    private final String host;
    private final int requestedPort;
    private final String path;
    private final String token;
    private final String serverName;
    private final String serverVersion;
    private final List<AgentTool> tools;
    private final int maxRequestBytes;
    private final ToolAssetResolver assetResolver;
    private final HttpClient upstreamClient;

    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile byte[] expectedAuthorization;
    private volatile int boundPort = -1;
    private volatile int internalPort = -1;
    private volatile boolean started;
    private HttpServer frontServer;
    private AgentToMcpServer internalServer;
    private ExecutorService frontExecutor;

    public BotMcpServer(String host, int port, String path, String token,
                        String serverName, String serverVersion, List<AgentTool> tools) {
        this(host, port, path, token, serverName, serverVersion, tools,
                DEFAULT_MAX_REQUEST_BYTES);
    }

    public BotMcpServer(String host, int port, String path, String token,
                        String serverName, String serverVersion, List<AgentTool> tools,
                        int maxRequestBytes) {
        this(host, port, path, token, serverName, serverVersion, tools,
                maxRequestBytes, ToolAssetResolver.none());
    }

    public BotMcpServer(String host, int port, String path, String token,
                        String serverName, String serverVersion, List<AgentTool> tools,
                        ToolAssetResolver assetResolver) {
        this(host, port, path, token, serverName, serverVersion, tools,
                DEFAULT_MAX_REQUEST_BYTES, assetResolver);
    }

    public BotMcpServer(String host, int port, String path, String token,
                        String serverName, String serverVersion, List<AgentTool> tools,
                        int maxRequestBytes, ToolAssetResolver assetResolver) {
        this.host = requireText(host, "host");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port 必须在 0..65535");
        }
        this.requestedPort = port;
        this.path = requireText(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须以 / 开头");
        }
        this.token = requireToken(token);
        this.serverName = requireText(serverName, "serverName");
        this.serverVersion = requireText(serverVersion, "serverVersion");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes 必须为正");
        }
        this.maxRequestBytes = maxRequestBytes;
        this.assetResolver = Objects.requireNonNull(assetResolver, "assetResolver");
        this.upstreamClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Bot MCP 已关闭，不能重新启动");
            }
            if (started) {
                return;
            }
            if (!isLoopbackHost(host)) {
                throw new IllegalStateException("Bot MCP 拒绝非回环 host: " + host);
            }

            AgentToMcpServer internal = null;
            HttpServer front = null;
            ExecutorService executor = null;
            boolean frontStarted = false;
            try {
                ToolRegistry registry = new ToolRegistry();
                for (AgentTool tool : tools) {
                    registry.register(new BotToolCallLoggingTool(tool));
                }
                internal = AgentToMcpServer.startHttp(
                        INTERNAL_HOST, 0, INTERNAL_PATH, registry, serverName, serverVersion,
                        supervisorContext(tools), ToolCallAuthorizer.standard(), assetResolver);
                if (internal.boundPort() <= 0) {
                    throw new IllegalStateException("内部 Bot MCP 未返回有效端口");
                }
                executor = Executors.newVirtualThreadPerTaskExecutor();
                front = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
                front.setExecutor(executor);
                front.createContext("/", this::handleProxy);
                front.start();
                frontStarted = true;

                this.internalServer = internal;
                this.frontServer = front;
                this.frontExecutor = executor;
                this.internalPort = internal.boundPort();
                this.boundPort = front.getAddress().getPort();
                this.expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
                this.started = true;
                LOG.info("Bot MCP 已启动 host={} port={} path={} tools={}",
                        host, boundPort, path, tools.size());
                OpsLog.info("event=mcp_started host={} port={} path={} tools={}",
                        host, boundPort, path, tools.size());
            } catch (IOException | RuntimeException failure) {
                if (front != null && frontStarted) {
                    try {
                        front.stop(0);
                    } catch (RuntimeException ignored) {
                        // 保留原始启动异常。
                    }
                }
                if (internal != null) {
                    try {
                        internal.close();
                    } catch (RuntimeException ignored) {
                        // 同上。
                    }
                }
                if (executor != null) {
                    executor.shutdownNow();
                }
                throw new IllegalStateException("Bot MCP 启动失败", failure);
            }
        }
    }

    public int boundPort() {
        if (!started || boundPort <= 0) {
            throw new IllegalStateException("Bot MCP 尚未启动");
        }
        return boundPort;
    }

    public String path() {
        return path;
    }

    public List<String> toolNames() {
        return tools.stream().map(AgentTool::name).toList();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean wasStarted = started;
            HttpServer front = frontServer;
            frontServer = null;
            if (front != null) {
                try {
                    front.stop(0);
                } catch (RuntimeException ignored) {
                    // 幂等关闭。
                }
            }
            AgentToMcpServer internal = internalServer;
            internalServer = null;
            if (internal != null) {
                try {
                    internal.close();
                } catch (RuntimeException ignored) {
                    // 同上。
                }
            }
            ExecutorService executor = frontExecutor;
            frontExecutor = null;
            if (executor != null) {
                executor.shutdownNow();
            }
            byte[] expected = expectedAuthorization;
            if (expected != null) {
                Arrays.fill(expected, (byte) 0);
                expectedAuthorization = null;
            }
            started = false;
            if (wasStarted) {
                OpsLog.info("event=mcp_stopped");
            }
        }
    }

    private ToolContext supervisorContext(List<AgentTool> exposedTools) {
        Set<String> names = new LinkedHashSet<>();
        exposedTools.forEach(tool -> names.add(tool.name()));
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(names.toArray(String[]::new))
                .roles(RoleSet.of(SUPERVISOR_ROLE))
                .sensitiveAllowed(true)
                .destructiveAllowed(true)
                .readOnly(false)
                .build();
        return ToolContext.of(AccessToken.DEFAULT, permissions, AgentIdentity.external());
    }

    private void handleProxy(HttpExchange exchange) throws IOException {
        try {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                OpsLog.warn("event=mcp_request_rejected reason=unknown_path method={}",
                        exchange.getRequestMethod());
                sendText(exchange, 404, "未知路径");
                return;
            }
            if (!authorized(exchange)) {
                OpsLog.warn("event=mcp_auth_denied remote={} method={}",
                        exchange.getRemoteAddress(), exchange.getRequestMethod());
                exchange.getResponseHeaders().set("WWW-Authenticate",
                        "Bearer realm=\"iibot-bot\"");
                sendText(exchange, 401, "未授权");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"POST".equals(method) && !"DELETE".equals(method)) {
                OpsLog.warn("event=mcp_request_rejected reason=method_not_allowed method={}",
                        method);
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
        return MessageDigest.isEqual(expected, actual);
    }

    private void proxy(HttpExchange exchange, String method) throws IOException {
        byte[] requestBody = null;
        if ("POST".equals(method)) {
            requestBody = readBoundedBody(exchange);
            if (requestBody == null) {
                return;
            }
        }
        URI upstreamUri = internalUri(exchange.getRequestURI().getRawQuery());
        HttpRequest.Builder request = HttpRequest.newBuilder(upstreamUri);
        copyRequestHeaders(exchange.getRequestHeaders(), request);
        request.method(method, "POST".equals(method)
                ? HttpRequest.BodyPublishers.ofByteArray(requestBody)
                : HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> response;
        try {
            response = upstreamClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            OpsLog.warn("event=mcp_upstream_error method={} code=INTERRUPTED", method);
            sendText(exchange, 502, "内部 MCP 请求被中断");
            return;
        } catch (IOException failure) {
            OpsLog.error("event=mcp_upstream_error method={} errorType={}",
                    method, failure.getClass().getSimpleName());
            sendText(exchange, 502, "内部 MCP 不可达");
            return;
        }

        int status = response.statusCode();
        OpsLog.info("event=mcp_http_request method={} upstreamStatus={}", method, status);
        copyResponseHeaders(response.headers(), exchange.getResponseHeaders());
        if (status < 200 || status == 204 || status == 304) {
            try {
                response.body().close();
            } finally {
                exchange.sendResponseHeaders(status, -1);
            }
            return;
        }
        long length = response.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH)
                .orElse(-1L);
        exchange.sendResponseHeaders(status, length >= 0 ? length : 0);
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
            // 客户端断开或上游关闭；不把内部错误回显给客户端。
        }
    }

    private URI internalUri(String rawQuery) {
        String query = rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery;
        return URI.create("http://" + INTERNAL_HOST + ":" + internalPort + INTERNAL_PATH + query);
    }

    private static void copyRequestHeaders(Headers incoming, HttpRequest.Builder request) {
        incoming.forEach((name, values) -> {
            if (!REQUEST_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                request.header(name, value);
            }
        });
    }

    private static void copyResponseHeaders(java.net.http.HttpHeaders incoming,
                                            Headers outgoing) {
        incoming.map().forEach((name, values) -> {
            if (!RESPONSE_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                outgoing.add(name, value);
            }
        });
    }

    private byte[] readBoundedBody(HttpExchange exchange) throws IOException {
        String rawLength = exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        if (rawLength != null && !rawLength.isBlank()) {
            try {
                long declared = Long.parseLong(rawLength.trim());
                if (declared < 0L) {
                    sendText(exchange, 400, "Content-Length 非法");
                    return null;
                }
                if (declared > maxRequestBytes) {
                    sendText(exchange, 413, "请求体过大");
                    return null;
                }
            } catch (NumberFormatException invalid) {
                sendText(exchange, 400, "Content-Length 非法");
                return null;
            }
        }
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try (InputStream input = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxRequestBytes) {
                    sendText(exchange, 413, "请求体过大");
                    return null;
                }
                collected.write(buffer, 0, read);
            }
        }
        return collected.toByteArray();
    }

    private static void sendText(HttpExchange exchange, int status, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static boolean isLoopbackHost(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException unresolved) {
            return false;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String requireToken(String value) {
        String normalized = requireText(value, "token");
        for (int index = 0; index < normalized.length(); index++) {
            char c = normalized.charAt(index);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("token 含空白或控制字符");
            }
        }
        return normalized;
    }
}
