package io.mosire.iibot.app.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 密钥来源端口。
 *
 * <p>配置只保存环境变量名或显式注入的来源名；真实密钥值只从本端口读取。实现不得把值写入
 * 日志、异常消息、事件 payload 或 {@code toString()}。测试可注入固定 Map，生产使用
 * {@link #environment()}。读取失败时由调用方 fail-closed，不做“无密钥也能发”的静默降级。</p>
 */
@FunctionalInterface
public interface SecretProvider {

    /** 查询指定来源的密钥；不存在或空值时返回 {@link Optional#empty()}。 */
    Optional<String> lookup(String name);

    /** 从进程环境变量读取。 */
    static SecretProvider environment() {
        return name -> {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            String value = System.getenv(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        };
    }

    /** 测试/显式注入用的不可变 Map 来源。 */
    static SecretProvider of(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        Map<String, String> copy = Map.copyOf(values);
        return name -> name == null ? Optional.empty() : Optional.ofNullable(copy.get(name))
                .filter(value -> !value.isBlank());
    }
}
