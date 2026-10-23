package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.tool.AgentTool;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * QQ 连接能力到 AgentLib 标准 {@link AgentTool} 的工厂。
 *
 * <p>工厂生成四个工具：</p>
 *
 * <ul>
 *   <li>{@code qq_list_adapters}：GUEST、只读、列出适配器状态摘要；</li>
 *   <li>{@code qq_adapter_status}：GUEST、只读、查询单适配器状态；</li>
 *   <li>{@code qq_get_login_info}：GUEST、只读、查询标准账号公开信息；</li>
 *   <li>{@code qq_send_message}：DEFAULT、sensitive、非破坏、发送纯文本并等待三态回执。</li>
 * </ul>
 *
 * <p>工具统一声明 {@code qq} 资源命名空间且缺省策略为 {@link io.mosire.agentlib.permission.ResourcePolicy#DENY}；
 * 宿主必须在调用者权限集里显式授予 qq 可达面。角色限制默认不表态（任意角色），可通过 builder 显式配置。</p>
 *
 * <p>本工厂与 {@link ConnectToolSet} 均不提供绕过 {@code ToolCallAuthorizer} 的 execute 便利方法。</p>
 */
public final class ConnectAgentTools {

    /** 列出适配器工具名。 */
    public static final String LIST_ADAPTERS = QqListAdaptersTool.NAME;

    /** 查询适配器状态工具名。 */
    public static final String ADAPTER_STATUS = QqAdapterStatusTool.NAME;

    /** 查询 Bot 账号信息工具名。 */
    public static final String GET_LOGIN_INFO = QqGetLoginInfoTool.NAME;

    /** 发送消息工具名。 */
    public static final String SEND_MESSAGE = QqSendMessageTool.NAME;

    private ConnectAgentTools() {
    }

    /**
     * 使用默认选项构建工具集。
     *
     * @param adapters 非 null 适配器注册表
     * @return 不可变工具集
     */
    public static ConnectToolSet create(AdapterRegistry adapters) {
        return create(adapters, ConnectToolOptions.defaults());
    }

    /**
     * 使用显式选项构建工具集。
     *
     * @param adapters 非 null 适配器注册表
     * @param options  非 null 选项
     * @return 不可变工具集
     */
    public static ConnectToolSet create(AdapterRegistry adapters, ConnectToolOptions options) {
        Objects.requireNonNull(adapters, "adapters");
        Objects.requireNonNull(options, "options");
        return new ConnectToolSet(List.of(
                new QqListAdaptersTool(adapters, options.listAdapterRoles()),
                new QqAdapterStatusTool(adapters, options.adapterStatusRoles()),
                new QqGetLoginInfoTool(
                        adapters, options.getLoginInfoRoles(), options.accountInfoTimeout()),
                new QqSendMessageTool(
                        adapters, options.sendMessageRoles(), options.sendTimeout())));
    }

    /**
     * {@link #create(AdapterRegistry)} 的语义别名。
     *
     * @param adapters 非 null 适配器注册表
     * @return 不可变工具集
     */
    public static ConnectToolSet standard(AdapterRegistry adapters) {
        return create(adapters);
    }

    /**
     * 直接返回工具列表的便捷入口。
     *
     * @param adapters 非 null 适配器注册表
     * @return 不可变工具列表
     */
    public static List<AgentTool> tools(AdapterRegistry adapters) {
        return create(adapters).tools();
    }

    /**
     * 创建可配置 builder。
     *
     * @param adapters 非 null 适配器注册表
     * @return builder
     */
    public static Builder builder(AdapterRegistry adapters) {
        return new Builder(adapters);
    }

    /** 工具集 builder。 */
    public static final class Builder {

        private final AdapterRegistry adapters;
        private final ConnectToolOptions.Builder options = ConnectToolOptions.builder();

        private Builder(AdapterRegistry adapters) {
            this.adapters = Objects.requireNonNull(adapters, "adapters");
        }

        /** 设置发送回执超时。 */
        public Builder sendTimeout(Duration timeout) {
            options.sendTimeout(timeout);
            return this;
        }

        /** 以毫秒设置发送回执超时。 */
        public Builder sendTimeoutMillis(long timeoutMillis) {
            options.sendTimeoutMillis(timeoutMillis);
            return this;
        }

        /** 设置账号信息查询超时。 */
        public Builder accountInfoTimeout(Duration timeout) {
            options.accountInfoTimeout(timeout);
            return this;
        }

        /** 以毫秒设置账号信息查询超时。 */
        public Builder accountInfoTimeoutMillis(long timeoutMillis) {
            options.accountInfoTimeoutMillis(timeoutMillis);
            return this;
        }

        /** 同时设置四个工具的角色限制；不调用则默认不限制角色。 */
        public Builder roles(String... roles) {
            options.roles(roles);
            return this;
        }

        /** 同时设置四个工具的角色限制；不调用则默认不限制角色。 */
        public Builder roles(Collection<String> roles) {
            options.roles(roles);
            return this;
        }

        /** 设置 {@code qq_list_adapters} 的角色限制。 */
        public Builder listAdapterRoles(String... roles) {
            options.listAdapterRoles(roles);
            return this;
        }

        /** 设置 {@code qq_list_adapters} 的角色限制。 */
        public Builder listAdapterRoles(Collection<String> roles) {
            options.listAdapterRoles(roles);
            return this;
        }

        /** 设置 {@code qq_adapter_status} 的角色限制。 */
        public Builder adapterStatusRoles(String... roles) {
            options.adapterStatusRoles(roles);
            return this;
        }

        /** 设置 {@code qq_adapter_status} 的角色限制。 */
        public Builder adapterStatusRoles(Collection<String> roles) {
            options.adapterStatusRoles(roles);
            return this;
        }

        /** 设置 {@code qq_get_login_info} 的角色限制。 */
        public Builder getLoginInfoRoles(String... roles) {
            options.getLoginInfoRoles(roles);
            return this;
        }

        /** 设置 {@code qq_get_login_info} 的角色限制。 */
        public Builder getLoginInfoRoles(Collection<String> roles) {
            options.getLoginInfoRoles(roles);
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

        /** 设置 {@code qq_send_message} 的角色限制。 */
        public Builder sendMessageRoles(String... roles) {
            options.sendMessageRoles(roles);
            return this;
        }

        /** 设置 {@code qq_send_message} 的角色限制。 */
        public Builder sendMessageRoles(Collection<String> roles) {
            options.sendMessageRoles(roles);
            return this;
        }

        /**
         * 使用外部选项替换当前选项，随后仍可继续调用本 builder 的单项覆盖方法。
         *
         * @param value 非 null 选项
         * @return 本 builder
         */
        public Builder options(ConnectToolOptions value) {
            Objects.requireNonNull(value, "options");
            options.sendTimeout(value.sendTimeout())
                    .accountInfoTimeout(value.accountInfoTimeout())
                    .roles(value.listAdapterRoles().toArray(String[]::new))
                    .listAdapterRoles(value.listAdapterRoles().toArray(String[]::new))
                    .adapterStatusRoles(value.adapterStatusRoles().toArray(String[]::new))
                    .sendMessageRoles(value.sendMessageRoles().toArray(String[]::new))
                    .getLoginInfoRoles(value.getLoginInfoRoles().toArray(String[]::new));
            return this;
        }

        /**
         * 构建工具集。
         *
         * @return 不可变工具集
         */
        public ConnectToolSet build() {
            return create(adapters, options.build());
        }
    }
}
