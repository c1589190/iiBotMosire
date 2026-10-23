package io.mosire.iibot.taskflow.control;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.Map;

/**
 * 工具结果专用的小型 JSON 序列化器。
 *
 * <p>本类只接受由工具显式构造的受限类型树（Map/Iterable/数组/字符串/数字/布尔/null），
 * 不反射任意 Java 对象。这样 {@link io.mosire.agentlib.tool.ToolContext}、异常内部结构、
 * token 或外部协议对象不会因为一次“顺手序列化”而进入模型上下文。</p>
 */
final class ControlJson {

    private ControlJson() {
    }

    /** 把受限类型树写成 JSON 文本。 */
    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
            return;
        }
        if (value instanceof String text) {
            quote(output, text);
            return;
        }
        if (value instanceof CharSequence text) {
            quote(output, text.toString());
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            quote(output, enumValue.name());
            return;
        }
        if (value instanceof Instant instant) {
            quote(output, instant.toString());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                quote(output, String.valueOf(entry.getKey()));
                output.append(':');
                append(output, entry.getValue());
            }
            output.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                append(output, item);
            }
            output.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            output.append('[');
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    output.append(',');
                }
                append(output, Array.get(value, index));
            }
            output.append(']');
            return;
        }
        // 未预期类型不展开为对象字段：只给出固定占位符，避免泄露任意对象的内部状态。
        quote(output, "[unsupported]");
    }

    private static void quote(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
