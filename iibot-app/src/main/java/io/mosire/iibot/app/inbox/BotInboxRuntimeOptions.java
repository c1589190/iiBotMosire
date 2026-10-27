package io.mosire.iibot.app.inbox;

import io.mosire.inbox.server.ServerConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Bot-first embedded Inbox 启动选项。
 *
 * <p>与 {@code EmbeddedInboxOptions} 对齐，但只暴露 iiBot 允许部署配置的字段；
 * API/MCP 仍只允许回环地址。端口 0 表示随机端口。</p>
 */
public record BotInboxRuntimeOptions(
        Path dataDir,
        String inboxId,
        String bindingLabel,
        Set<String> channels,
        long bindingTtlHours,
        String apiHost,
        int apiPort,
        String mcpHost,
        int mcpPort,
        String mcpPath,
        Duration heartbeat,
        Duration claimSweepInterval) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 0;
    public static final String DEFAULT_MCP_PATH = "/mcp";
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_CLAIM_SWEEP_INTERVAL = Duration.ofSeconds(5);

    public BotInboxRuntimeOptions {
        dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        inboxId = requireText(inboxId, "inboxId");
        bindingLabel = requireText(bindingLabel, "bindingLabel");
        channels = channels == null || channels.isEmpty()
                ? Set.of("qq") : Set.copyOf(new LinkedHashSet<>(channels));
        if (bindingTtlHours <= 0 || bindingTtlHours > 24L * 365L) {
            throw new IllegalArgumentException("bindingTtlHours 超出范围");
        }
        apiHost = requireLoopback(apiHost, "apiHost");
        mcpHost = requireLoopback(mcpHost, "mcpHost");
        apiPort = requirePort(apiPort, "apiPort");
        mcpPort = requirePort(mcpPort, "mcpPort");
        mcpPath = requireMcpPath(mcpPath);
        heartbeat = positive(heartbeat, "heartbeat");
        claimSweepInterval = positive(claimSweepInterval, "claimSweepInterval");
    }

    /** 旧调用使用的默认网络参数。 */
    public static BotInboxRuntimeOptions defaults(
            Path dataDir, String inboxId, String bindingLabel,
            Set<String> channels, long bindingTtlHours) {
        return new BotInboxRuntimeOptions(dataDir, inboxId, bindingLabel, channels,
                bindingTtlHours, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_HOST, DEFAULT_PORT,
                DEFAULT_MCP_PATH, DEFAULT_HEARTBEAT, DEFAULT_CLAIM_SWEEP_INTERVAL);
    }

    private static int requirePort(int port, String name) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(name + " 必须在 0..65535");
        }
        return port;
    }

    private static String requireLoopback(String host, String name) {
        String normalized = requireText(host, name);
        if (!ServerConfig.isLoopback(normalized)) {
            throw new IllegalArgumentException(name + " 只允许回环地址: " + normalized);
        }
        return normalized;
    }

    private static String requireMcpPath(String value) {
        String normalized = requireText(value, "mcpPath");
        if (!normalized.startsWith("/") || normalized.contains("?")
                || normalized.contains("#") || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "mcpPath 必须是以 / 开头且不含 query/fragment/空白的绝对路径");
        }
        return normalized;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
