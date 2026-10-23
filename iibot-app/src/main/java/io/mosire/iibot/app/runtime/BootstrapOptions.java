package io.mosire.iibot.app.runtime;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.connect.core.MessagingAdapter;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 组合根的可注入依赖。
 *
 * <p>生产使用 {@link #production(SecretProvider)}；测试可注入 Fake LlmClient 与 Fake
 * MessagingAdapter，从而不走真实网络。为 {@code null} 的 LLM/adapter 表示“按配置构造真实
 * 实现”；非 null 则直接使用。{@code llmRoutes} 可进一步为命名 route 注入独立测试客户端，
 * 未注入的 route 回退 {@code llm}。</p>
 *
 * @param secrets     密钥来源
 * @param clock       宿主时钟
 * @param llm         可选默认 LLM 覆盖（测试）
 * @param adapter     可选消息适配器覆盖（测试）
 * @param controlPlane 控制面钩子；默认 {@link NoopControlPlane}
 * @param llmRoutes   可选命名 LLM 路由覆盖（测试/显式注入）
 */
public record BootstrapOptions(
        SecretProvider secrets,
        Clock clock,
        LlmClient llm,
        MessagingAdapter adapter,
        ControlPlane controlPlane,
        Map<String, LlmClient> llmRoutes) {

    public BootstrapOptions {
        secrets = Objects.requireNonNull(secrets, "secrets");
        clock = clock == null ? Clock.systemUTC() : clock;
        controlPlane = controlPlane == null ? NoopControlPlane.INSTANCE : controlPlane;
        if (llmRoutes == null || llmRoutes.isEmpty()) {
            llmRoutes = Map.of();
        } else {
            Map<String, LlmClient> copy = new LinkedHashMap<>();
            llmRoutes.forEach((route, client) -> copy.put(
                    Objects.requireNonNull(route, "llmRoutes route"),
                    Objects.requireNonNull(client, "llmRoutes." + route)));
            llmRoutes = Collections.unmodifiableMap(copy);
        }
    }

    /** 兼容既有五参构造：无命名 route 覆盖。 */
    public BootstrapOptions(SecretProvider secrets, Clock clock, LlmClient llm,
                            MessagingAdapter adapter, ControlPlane controlPlane) {
        this(secrets, clock, llm, adapter, controlPlane, Map.of());
    }

    /** 生产默认：环境变量密钥 + 系统时钟 + 真实实现 + 未启用控制面。 */
    public static BootstrapOptions production(SecretProvider secrets) {
        return new BootstrapOptions(secrets, Clock.systemUTC(), null, null,
                NoopControlPlane.INSTANCE, Map.of());
    }

    /** 测试方便：显式注入 Fake LLM 与 Fake adapter。 */
    public static BootstrapOptions fake(SecretProvider secrets, Clock clock, LlmClient llm,
                                        MessagingAdapter adapter) {
        return new BootstrapOptions(secrets, clock, llm, adapter,
                NoopControlPlane.INSTANCE, Map.of());
    }

    /** 测试方便：默认 Fake LLM + 可选命名 route Fake LLM。 */
    public static BootstrapOptions fake(SecretProvider secrets, Clock clock, LlmClient llm,
                                        MessagingAdapter adapter,
                                        Map<String, LlmClient> llmRoutes) {
        return new BootstrapOptions(secrets, clock, llm, adapter,
                NoopControlPlane.INSTANCE, llmRoutes);
    }

    /** 为给定 route 选择注入客户端；缺失时回退调用方给出的 default。 */
    public LlmClient llmForRoute(String route, LlmClient defaultClient) {
        if (route == null || route.isBlank()) {
            return Objects.requireNonNull(defaultClient, "defaultClient");
        }
        return llmRoutes.getOrDefault(route, defaultClient);
    }
}
