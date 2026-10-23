package io.mosire.iibot.connect.agent;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * QQ 连接工具的可配置选项。
 *
 * <p>所有选项均采用不可变快照。角色限制为空集表示“本维不表态”，即任意角色均可调用；
 * 非空时由 AgentLib 的 {@code ToolExecutionGuard} 与调用者角色求交，工具自身不做权限判定。</p>
 *
 * @param sendTimeout         等待发送回执的最长时间，默认 15 秒
 * @param listAdapterRoles    {@code qq_list_adapters} 的角色限制
 * @param adapterStatusRoles  {@code qq_adapter_status} 的角色限制
 * @param sendMessageRoles    {@code qq_send_message} 的角色限制
 * @param accountInfoTimeout  等待账号信息查询完成的最长时间，默认 5 秒
 * @param getLoginInfoRoles   {@code qq_get_login_info} 的角色限制
 */
public record ConnectToolOptions(
        Duration sendTimeout,
        Set<String> listAdapterRoles,
        Set<String> adapterStatusRoles,
        Set<String> sendMessageRoles,
        Duration accountInfoTimeout,
        Set<String> getLoginInfoRoles) {

    /** 默认发送回执等待时间：15 秒。 */
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(15);

    /** 默认账号信息查询等待时间：5 秒。 */
    public static final Duration DEFAULT_ACCOUNT_INFO_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 兼容旧版四参数构造：账号信息查询使用默认超时，且不限制 {@code qq_get_login_info} 角色。
     *
     * @param sendTimeout        发送回执超时
     * @param listAdapterRoles   列表工具角色限制
     * @param adapterStatusRoles 状态工具角色限制
     * @param sendMessageRoles   发送工具角色限制
     */
    public ConnectToolOptions(
            Duration sendTimeout,
            Set<String> listAdapterRoles,
            Set<String> adapterStatusRoles,
            Set<String> sendMessageRoles) {
        this(sendTimeout, listAdapterRoles, adapterStatusRoles, sendMessageRoles,
                DEFAULT_ACCOUNT_INFO_TIMEOUT, Set.of());
    }

    /**
     * 规范化并校验选项。
     *
     * @throws IllegalArgumentException 超时非正或角色名为空白
     */
    public ConnectToolOptions {
        sendTimeout = requirePositiveTimeout(sendTimeout, "sendTimeout");
        accountInfoTimeout = requirePositiveTimeout(accountInfoTimeout, "accountInfoTimeout");
        listAdapterRoles = normalizeRoles(listAdapterRoles);
        adapterStatusRoles = normalizeRoles(adapterStatusRoles);
        sendMessageRoles = normalizeRoles(sendMessageRoles);
        getLoginInfoRoles = normalizeRoles(getLoginInfoRoles);
    }

    /** 默认选项：15 秒发送超时、5 秒账号信息超时、不限制角色。 */
    public static ConnectToolOptions defaults() {
        return new ConnectToolOptions(
                DEFAULT_SEND_TIMEOUT,
                Set.of(),
                Set.of(),
                Set.of(),
                DEFAULT_ACCOUNT_INFO_TIMEOUT,
                Set.of());
    }

    /** 创建选项 builder。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建一个只设置发送超时的选项。 */
    public static ConnectToolOptions withSendTimeout(Duration sendTimeout) {
        return builder().sendTimeout(sendTimeout).build();
    }

    /** 创建一个只设置账号信息查询超时的选项。 */
    public static ConnectToolOptions withAccountInfoTimeout(Duration accountInfoTimeout) {
        return builder().accountInfoTimeout(accountInfoTimeout).build();
    }

    private static Duration requirePositiveTimeout(Duration timeout, String fieldName) {
        Duration value = Objects.requireNonNull(timeout, fieldName);
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须至少为 1 毫秒");
        }
        return value;
    }

    /**
     * 把一个角色集合规范为去空白、去重、不可变的有序集合。
     *
     * @param roles 原始角色集合；null 或空视为不限制
     * @return 不可变角色集合
     */
    static Set<String> normalizeRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new TreeSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("角色名不能为空白");
            }
            normalized.add(role.trim());
        }
        return Set.copyOf(normalized);
    }

    /** {@link ConnectToolOptions} 的可变 builder。 */
    public static final class Builder {

        private Duration sendTimeout = DEFAULT_SEND_TIMEOUT;
        private Duration accountInfoTimeout = DEFAULT_ACCOUNT_INFO_TIMEOUT;
        private Set<String> listAdapterRoles = Set.of();
        private Set<String> adapterStatusRoles = Set.of();
        private Set<String> sendMessageRoles = Set.of();
        private Set<String> getLoginInfoRoles = Set.of();

        private Builder() {
        }

        /** 设置发送回执超时。 */
        public Builder sendTimeout(Duration value) {
            sendTimeout = Objects.requireNonNull(value, "sendTimeout");
            return this;
        }

        /** 以毫秒设置发送回执超时。 */
        public Builder sendTimeoutMillis(long value) {
            return sendTimeout(Duration.ofMillis(value));
        }

        /** 设置账号信息查询超时。 */
        public Builder accountInfoTimeout(Duration value) {
            accountInfoTimeout = Objects.requireNonNull(value, "accountInfoTimeout");
            return this;
        }

        /** 以毫秒设置账号信息查询超时。 */
        public Builder accountInfoTimeoutMillis(long value) {
            return accountInfoTimeout(Duration.ofMillis(value));
        }

        /** 同时设置四个工具的角色限制；空参数表示清除限制。 */
        public Builder roles(String... roles) {
            return roles(roles == null ? Set.of() : Arrays.asList(roles));
        }

        /** 同时设置四个工具的角色限制；空集合表示清除限制。 */
        public Builder roles(Collection<String> roles) {
            Set<String> normalized = normalizeRoles(roles);
            listAdapterRoles = normalized;
            adapterStatusRoles = normalized;
            sendMessageRoles = normalized;
            getLoginInfoRoles = normalized;
            return this;
        }

        /** 设置 {@code qq_list_adapters} 的角色限制。 */
        public Builder listAdapterRoles(String... roles) {
            return listAdapterRoles(roles == null ? Set.of() : Arrays.asList(roles));
        }

        /** 设置 {@code qq_list_adapters} 的角色限制。 */
        public Builder listAdapterRoles(Collection<String> roles) {
            listAdapterRoles = normalizeRoles(roles);
            return this;
        }

        /** 设置 {@code qq_adapter_status} 的角色限制。 */
        public Builder adapterStatusRoles(String... roles) {
            return adapterStatusRoles(roles == null ? Set.of() : Arrays.asList(roles));
        }

        /** 设置 {@code qq_adapter_status} 的角色限制。 */
        public Builder adapterStatusRoles(Collection<String> roles) {
            adapterStatusRoles = normalizeRoles(roles);
            return this;
        }

        /** 设置 {@code qq_send_message} 的角色限制。 */
        public Builder sendMessageRoles(String... roles) {
            return sendMessageRoles(roles == null ? Set.of() : Arrays.asList(roles));
        }

        /** 设置 {@code qq_send_message} 的角色限制。 */
        public Builder sendMessageRoles(Collection<String> roles) {
            sendMessageRoles = normalizeRoles(roles);
            return this;
        }

        /** 设置 {@code qq_get_login_info} 的角色限制。 */
        public Builder getLoginInfoRoles(String... roles) {
            return getLoginInfoRoles(roles == null ? Set.of() : Arrays.asList(roles));
        }

        /** 设置 {@code qq_get_login_info} 的角色限制。 */
        public Builder getLoginInfoRoles(Collection<String> roles) {
            getLoginInfoRoles = normalizeRoles(roles);
            return this;
        }

        /** {@link #getLoginInfoRoles(String...)} 的短别名。 */
        public Builder loginInfoRoles(String... roles) {
            return getLoginInfoRoles(roles);
        }

        /** {@link #getLoginInfoRoles(Collection)} 的短别名。 */
        public Builder loginInfoRoles(Collection<String> roles) {
            return getLoginInfoRoles(roles);
        }

        /** 构建不可变选项。 */
        public ConnectToolOptions build() {
            return new ConnectToolOptions(
                    sendTimeout,
                    listAdapterRoles,
                    adapterStatusRoles,
                    sendMessageRoles,
                    accountInfoTimeout,
                    getLoginInfoRoles);
        }
    }
}
