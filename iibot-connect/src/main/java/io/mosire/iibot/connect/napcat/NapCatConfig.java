package io.mosire.iibot.connect.napcat;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * NapCat / OneBot 11 适配器配置。
 *
 * <p>安全约束：{@link #toString()} 只输出脱敏 URI，不输出 access token、用户信息
 * 或完整查询串。调用方也不应把本对象直接序列化后写日志。</p>
 */
public final class NapCatConfig {

    /** WebSocket 事件连接的 token 放置方式。 */
    public enum WsAuthMode {
        /** 仅发送 {@code Authorization: Bearer ...}。 */
        BEARER,
        /** 仅发送 {@code access_token} 查询参数。 */
        QUERY,
        /** 同时发送两种形式。 */
        BOTH
    }

    /**
     * HTTP 发送动作选择。
     *
     * <p>默认使用 OneBot 通用 {@code send_msg}；{@link #DEDICATED} 则按目标
     * 使用 {@code send_private_msg} / {@code send_group_msg}。</p>
     */
    public enum SendActionMode {
        SEND_MSG,
        DEDICATED
    }

    private final URI eventWebSocketUri;
    private final URI httpBaseUri;
    private final String accessToken;
    private final String accountId;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxTextLength;
    private final Duration initialReconnectDelay;
    private final Duration maxReconnectDelay;
    private final double reconnectJitter;
    private final Duration dedupeTtl;
    private final int maxDedupEntries;
    private final double rateLimitPerSecond;
    private final int rateLimitBurst;
    private final WsAuthMode wsAuthMode;
    private final SendActionMode sendActionMode;
    private final boolean suppressSelfMessages;

    private NapCatConfig(Builder builder) {
        this.eventWebSocketUri = builder.eventWebSocketUri;
        this.httpBaseUri = builder.httpBaseUri;
        this.accessToken = builder.accessToken;
        this.accountId = builder.accountId;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.maxTextLength = builder.maxTextLength;
        this.initialReconnectDelay = builder.initialReconnectDelay;
        this.maxReconnectDelay = builder.maxReconnectDelay;
        this.reconnectJitter = builder.reconnectJitter;
        this.dedupeTtl = builder.dedupeTtl;
        this.maxDedupEntries = builder.maxDedupEntries;
        this.rateLimitPerSecond = builder.rateLimitPerSecond;
        this.rateLimitBurst = builder.rateLimitBurst;
        this.wsAuthMode = builder.wsAuthMode;
        this.sendActionMode = builder.sendActionMode;
        this.suppressSelfMessages = builder.suppressSelfMessages;
    }

    /** 创建要求事件 WS URI 与 HTTP 基础 URI 的 builder。 */
    public static Builder builder(URI eventWebSocketUri, URI httpBaseUri) {
        return new Builder(eventWebSocketUri, httpBaseUri);
    }

    public URI eventWebSocketUri() {
        return eventWebSocketUri;
    }

    public URI httpBaseUri() {
        return httpBaseUri;
    }

    /** access token 的可选视图。 */
    public Optional<String> accessToken() {
        return Optional.ofNullable(accessToken);
    }

    /** 可选账号标识；未配置时由入站事件中的 self_id 提供。 */
    public Optional<String> accountId() {
        return Optional.ofNullable(accountId);
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int maxTextLength() {
        return maxTextLength;
    }

    public Duration initialReconnectDelay() {
        return initialReconnectDelay;
    }

    public Duration maxReconnectDelay() {
        return maxReconnectDelay;
    }

    public double reconnectJitter() {
        return reconnectJitter;
    }

    public Duration dedupeTtl() {
        return dedupeTtl;
    }

    public int maxDedupEntries() {
        return maxDedupEntries;
    }

    public double rateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public int rateLimitBurst() {
        return rateLimitBurst;
    }

    public WsAuthMode wsAuthMode() {
        return wsAuthMode;
    }

    public SendActionMode sendActionMode() {
        return sendActionMode;
    }

    /** 是否丢弃 self_id == user_id 的自身消息，默认 true。 */
    public boolean suppressSelfMessages() {
        return suppressSelfMessages;
    }

    @Override
    public String toString() {
        return "NapCatConfig{"
                + "eventWebSocketUri=" + safeUri(eventWebSocketUri)
                + ", httpBaseUri=" + safeUri(httpBaseUri)
                + ", accessToken=" + (accessToken == null ? "<absent>" : "***")
                + ", accountId=" + (accountId == null ? "<absent>" : accountId)
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", maxTextLength=" + maxTextLength
                + ", initialReconnectDelay=" + initialReconnectDelay
                + ", maxReconnectDelay=" + maxReconnectDelay
                + ", reconnectJitter=" + reconnectJitter
                + ", dedupeTtl=" + dedupeTtl
                + ", rateLimitPerSecond=" + rateLimitPerSecond
                + ", rateLimitBurst=" + rateLimitBurst
                + ", wsAuthMode=" + wsAuthMode
                + ", sendActionMode=" + sendActionMode
                + ", suppressSelfMessages=" + suppressSelfMessages
                + '}';
    }

    private static String safeUri(URI uri) {
        String scheme = uri.getScheme() == null ? "?" : uri.getScheme();
        String host = uri.getHost() == null ? "?" : uri.getHost();
        int port = uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path
                + (uri.getRawQuery() == null ? "" : "?<redacted>");
    }

    /** 配置构造器。所有参数在 {@link #build()} 时统一校验。 */
    public static final class Builder {
        private final URI eventWebSocketUri;
        private final URI httpBaseUri;
        private String accessToken;
        private String accountId;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private int maxTextLength = 2_000;
        private Duration initialReconnectDelay = Duration.ofMillis(500);
        private Duration maxReconnectDelay = Duration.ofSeconds(30);
        private double reconnectJitter = 0.2;
        private Duration dedupeTtl = Duration.ofMinutes(5);
        private int maxDedupEntries = 10_000;
        private double rateLimitPerSecond = 5.0;
        private int rateLimitBurst = 5;
        private WsAuthMode wsAuthMode = WsAuthMode.BOTH;
        private SendActionMode sendActionMode = SendActionMode.SEND_MSG;
        private boolean suppressSelfMessages = true;

        private Builder(URI eventWebSocketUri, URI httpBaseUri) {
            this.eventWebSocketUri = Objects.requireNonNull(eventWebSocketUri, "eventWebSocketUri");
            this.httpBaseUri = Objects.requireNonNull(httpBaseUri, "httpBaseUri");
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = blankToNull(accessToken);
            return this;
        }

        public Builder accountId(String accountId) {
            this.accountId = blankToNull(accountId);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder maxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
            return this;
        }

        public Builder initialReconnectDelay(Duration initialReconnectDelay) {
            this.initialReconnectDelay = initialReconnectDelay;
            return this;
        }

        public Builder maxReconnectDelay(Duration maxReconnectDelay) {
            this.maxReconnectDelay = maxReconnectDelay;
            return this;
        }

        public Builder reconnectJitter(double reconnectJitter) {
            this.reconnectJitter = reconnectJitter;
            return this;
        }

        public Builder dedupeTtl(Duration dedupeTtl) {
            this.dedupeTtl = dedupeTtl;
            return this;
        }

        public Builder maxDedupEntries(int maxDedupEntries) {
            this.maxDedupEntries = maxDedupEntries;
            return this;
        }

        public Builder rateLimitPerSecond(double rateLimitPerSecond) {
            this.rateLimitPerSecond = rateLimitPerSecond;
            return this;
        }

        public Builder rateLimitBurst(int rateLimitBurst) {
            this.rateLimitBurst = rateLimitBurst;
            return this;
        }

        public Builder wsAuthMode(WsAuthMode wsAuthMode) {
            this.wsAuthMode = wsAuthMode;
            return this;
        }

        public Builder sendActionMode(SendActionMode sendActionMode) {
            this.sendActionMode = sendActionMode;
            return this;
        }

        public Builder suppressSelfMessages(boolean suppressSelfMessages) {
            this.suppressSelfMessages = suppressSelfMessages;
            return this;
        }

        public NapCatConfig build() {
            validateUri(eventWebSocketUri, "eventWebSocketUri", "ws", "wss");
            validateUri(httpBaseUri, "httpBaseUri", "http", "https");
            requirePositive(connectTimeout, "connectTimeout");
            requirePositive(requestTimeout, "requestTimeout");
            if (maxTextLength <= 0) {
                throw new IllegalArgumentException("maxTextLength 必须大于 0");
            }
            requirePositive(initialReconnectDelay, "initialReconnectDelay");
            requirePositive(maxReconnectDelay, "maxReconnectDelay");
            if (maxReconnectDelay.compareTo(initialReconnectDelay) < 0) {
                throw new IllegalArgumentException("maxReconnectDelay 不能小于 initialReconnectDelay");
            }
            if (!(reconnectJitter >= 0.0 && reconnectJitter <= 1.0)) {
                throw new IllegalArgumentException("reconnectJitter 必须在 [0, 1] 内");
            }
            requirePositive(dedupeTtl, "dedupeTtl");
            if (maxDedupEntries <= 0) {
                throw new IllegalArgumentException("maxDedupEntries 必须大于 0");
            }
            if (!(rateLimitPerSecond > 0.0) || !Double.isFinite(rateLimitPerSecond)) {
                throw new IllegalArgumentException("rateLimitPerSecond 必须为正有限数");
            }
            if (rateLimitBurst <= 0) {
                throw new IllegalArgumentException("rateLimitBurst 必须大于 0");
            }
            Objects.requireNonNull(wsAuthMode, "wsAuthMode");
            Objects.requireNonNull(sendActionMode, "sendActionMode");
            return new NapCatConfig(this);
        }

        private static void validateUri(URI uri, String name, String... schemes) {
            Objects.requireNonNull(uri, name);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(name + " 必须是绝对 URI");
            }
            boolean accepted = false;
            for (String scheme : schemes) {
                if (scheme.equalsIgnoreCase(uri.getScheme())) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                throw new IllegalArgumentException(name + " 的 scheme 不受支持");
            }
        }

        private static void requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " 必须为正数");
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
