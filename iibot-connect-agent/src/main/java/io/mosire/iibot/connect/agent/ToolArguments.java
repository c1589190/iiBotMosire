package io.mosire.iibot.connect.agent;

import java.util.Map;

/** 工具参数读取与路径安全校验。 */
final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * 读取必填非空白字符串参数。
     *
     * @param arguments 参数映射
     * @param name      字段名
     * @return 原样字符串（不做 trim，保持调用方提供值）
     * @throws InvalidToolArgumentException 缺失、类型错误或空白
     */
    static String requiredText(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidToolArgumentException("参数 " + name + " 必须是非空白字符串");
        }
        return text;
    }

    /**
     * 读取可选非空白字符串参数。
     *
     * @param arguments 参数映射
     * @param name      字段名
     * @return 缺失时为空串；提供空白或非字符串类型时抛参数错误
     */
    static String optionalText(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidToolArgumentException("参数 " + name + " 提供时必须是字符串");
        }
        return text;
    }

    /**
     * 校验参与资源路径的适配器/目标标识。
     *
     * <p>目标 id 来自模型参数，必须阻断 {@code /}、{@code \}、NUL、控制字符和相对路径段，
     * 防止 {@code ../} 改变资源可判定边界。</p>
     *
     * @param value     原始值
     * @param fieldName 字段名
     * @return 原值
     * @throws InvalidToolArgumentException 非法
     */
    static String requireSafePathIdentifier(String value, String fieldName) {
        try {
            return AdapterRegistry.requireSafeIdentifier(value, fieldName);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidToolArgumentException(invalid.getMessage());
        }
    }
}
