package io.mosire.iibot.app.mcp;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 外部控制 MCP 的传输层配置。
 *
 * <p>本记录<b>不保存 token 明文</b>：生产配置只保存环境变量名 {@link #bearerTokenEnv()}，
 * 真实值由 {@code SecretProvider} 在启动时读取。测试可通过
 * {@link ControlMcpServer.Builder#bearerToken(String)} 显式注入 token，但该值不会进入
 * 本记录、日志、异常或 {@code toString()}。</p>
 *
 * <p>显式 token 注入时允许 {@code bearerTokenEnv} 为空；启动仍会在既无注入值、
 * 也无环境变量名时 fail-closed。默认只允许回环 host；非回环地址不是“告警后继续”
 * 的配置，而是启动期硬拒绝（见 {@link ControlMcpServer#start()}）。</p>
 */
public record ControlMcpConfig(
        boolean enabled,
        String host,
        int port,
        String path,
        String bearerTokenEnv,
        int maxRequestBytes,
        PlatformPolicy platform) {

    /**
     * 平台能力权限策略。默认只允许当前推演群自主入团，任意发送关闭。
     *
     * @param allowArbitrarySend 是否允许通用私聊/群发送工具；默认 false
     * @param onboardingEnabled 是否允许公屏自主入团
     * @param onboardingAllowedGroupIds 入团群 allowlist；空集表示仅当前 publicGroupId，{"*"} 表示全部
     * @param onboardingRequireOptIn 是否要求来源消息含报名/加入意图
     * @param onboardingMaxPlayers 当前世界最大玩家数
     */
    public record PlatformPolicy(
            boolean allowArbitrarySend,
            boolean onboardingEnabled,
            Set<String> onboardingAllowedGroupIds,
            boolean onboardingRequireOptIn,
            int onboardingMaxPlayers) {

        public PlatformPolicy {
            onboardingAllowedGroupIds = onboardingAllowedGroupIds == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(onboardingAllowedGroupIds));
            if (onboardingAllowedGroupIds.stream().anyMatch(
                    value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("onboardingAllowedGroupIds 不能包含空白");
            }
            if (onboardingMaxPlayers < 1) {
                throw new IllegalArgumentException("onboardingMaxPlayers 必须为正");
            }
        }

        public static PlatformPolicy defaults() {
            return new PlatformPolicy(false, true, Set.of(), true, 64);
        }
    }

    /** 请求体上限缺省值：16 MiB。 */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 16 * 1024 * 1024;

    /** 缺省安全绑定地址。 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** 缺省控制路径。 */
    public static final String DEFAULT_PATH = "/mcp";

    public ControlMcpConfig {
        host = Objects.requireNonNullElse(host, DEFAULT_HOST).trim();
        path = Objects.requireNonNullElse(path, DEFAULT_PATH).trim();
        platform = platform == null ? PlatformPolicy.defaults() : platform;
        bearerTokenEnv = Objects.requireNonNullElse(bearerTokenEnv, "").trim();

        if (host.isEmpty()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port 必须在 0..65535 之间: " + port);
        }
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes 必须为正: " + maxRequestBytes);
        }
        if (path.isEmpty() || !path.startsWith("/") || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0 || path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("path 必须是不含 query/fragment/空白的绝对路径: " + path);
        }
    }

    /** 六参兼容构造：platform 取安全默认值。 */
    public ControlMcpConfig(boolean enabled, String host, int port, String path,
                            String bearerTokenEnv, int maxRequestBytes) {
        this(enabled, host, port, path, bearerTokenEnv, maxRequestBytes,
                PlatformPolicy.defaults());
    }

    /** 五参便捷构造：请求体上限取 {@link #DEFAULT_MAX_REQUEST_BYTES}。 */
    public ControlMcpConfig(boolean enabled, String host, int port, String path, String bearerTokenEnv) {
        this(enabled, host, port, path, bearerTokenEnv, DEFAULT_MAX_REQUEST_BYTES,
                PlatformPolicy.defaults());
    }

    /** 默认关闭且不接触任何网络资源的配置。 */
    public static ControlMcpConfig disabled() {
        return new ControlMcpConfig(false, DEFAULT_HOST, 0, DEFAULT_PATH, "", DEFAULT_MAX_REQUEST_BYTES);
    }

    /** 启用控制面的生产形态配置：只保存环境变量名。 */
    public static ControlMcpConfig enabled(String bearerTokenEnv) {
        return new ControlMcpConfig(true, DEFAULT_HOST, 0, DEFAULT_PATH, bearerTokenEnv,
                DEFAULT_MAX_REQUEST_BYTES);
    }

    /** 启用控制面的完整生产形态配置。 */
    public static ControlMcpConfig enabled(String host, int port, String path, String bearerTokenEnv) {
        return new ControlMcpConfig(true, host, port, path, bearerTokenEnv, DEFAULT_MAX_REQUEST_BYTES);
    }

    /** 同名配置换请求体上限（返回新不可变记录）。 */
    public ControlMcpConfig withMaxRequestBytes(int value) {
        return new ControlMcpConfig(enabled, host, port, path, bearerTokenEnv, value, platform);
    }
}
