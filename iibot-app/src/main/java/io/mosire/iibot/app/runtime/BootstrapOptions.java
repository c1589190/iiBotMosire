package io.mosire.iibot.app.runtime;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.connect.core.MessagingAdapter;

import java.time.Clock;
import java.util.Objects;

/**
 * 组合根的可注入依赖。
 *
 * <p>生产使用 {@link #production(SecretProvider)}；测试可注入 Fake LlmClient 与 Fake
 * MessagingAdapter，从而不走真实网络。为 {@code null} 的 LLM/adapter 表示“按配置构造真实
 * 实现”；非 null 则直接使用。</p>
 *
 * @param secrets     密钥来源
 * @param clock       宿主时钟
 * @param llm         可选 LLM 覆盖（测试）
 * @param adapter     可选消息适配器覆盖（测试）
 * @param controlPlane 控制面钩子；默认 {@link NoopControlPlane}
 */
public record BootstrapOptions(
        SecretProvider secrets,
        Clock clock,
        LlmClient llm,
        MessagingAdapter adapter,
        ControlPlane controlPlane) {

    public BootstrapOptions {
        secrets = Objects.requireNonNull(secrets, "secrets");
        clock = clock == null ? Clock.systemUTC() : clock;
        controlPlane = controlPlane == null ? NoopControlPlane.INSTANCE : controlPlane;
    }

    /** 生产默认：环境变量密钥 + 系统时钟 + 真实实现 + 未启用控制面。 */
    public static BootstrapOptions production(SecretProvider secrets) {
        return new BootstrapOptions(secrets, Clock.systemUTC(), null, null,
                NoopControlPlane.INSTANCE);
    }

    /** 测试方便：显式注入 Fake LLM 与 Fake adapter。 */
    public static BootstrapOptions fake(SecretProvider secrets, Clock clock, LlmClient llm,
                                        MessagingAdapter adapter) {
        return new BootstrapOptions(secrets, clock, llm, adapter, NoopControlPlane.INSTANCE);
    }
}
