package io.mosire.iibot.connect.agent;

import java.util.List;
import java.util.Map;

/**
 * 极小 JSON 序列化器，仅服务工具结果。
 *
 * <p>工具结果只由字符串、数字、布尔、列表和映射组成，且已在调用前脱敏。本类不反射任意对象，
 * 因此不会意外把底层协议对象、token 字段或异常内部结构带进结果。</p>
 */
final class Json {

    private Json() {
    }

    /**
     * 把受限类型树序列化为 JSON。
     *
     * @param value 映射、列表、字符串、数字、布尔或 null
     * @return JSON 文本
     */
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
        if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
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
        if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                append(output, list.get(index));
            }
            output.append(']');
            return;
        }
        // 未知类型不展开：调用方只应放受限类型；这里退化为字符串也不会泄露对象内部字段。
        quote(output, String.valueOf(value));
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
