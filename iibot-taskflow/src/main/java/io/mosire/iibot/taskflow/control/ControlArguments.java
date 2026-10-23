package io.mosire.iibot.taskflow.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 控制面工具的参数读取与类型校验，全部失败统一映射为 {@code INVALID_ARGUMENTS}。 */
final class ControlArguments {

    private ControlArguments() {
    }

    static String requiredText(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是非空白字符串");
        }
        return text;
    }

    static String optionalText(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new InvalidControlArgumentException("参数 " + name + " 提供时必须是字符串");
        }
        return text;
    }

    static long requiredNonNegativeLong(Map<String, Object> arguments, String name) {
        long value = requiredLong(arguments, name);
        if (value < 0) {
            throw new InvalidControlArgumentException("参数 " + name + " 不能为负");
        }
        return value;
    }

    static long requiredPositiveLong(Map<String, Object> arguments, String name) {
        long value = requiredLong(arguments, name);
        if (value <= 0) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须为正");
        }
        return value;
    }

    static long requiredLong(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof Number number) || !isIntegral(number)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是整数");
        }
        return number.longValue();
    }

    static long optionalNonNegativeLong(Map<String, Object> arguments, String name, long defaultValue) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number) || !isIntegral(number)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是整数");
        }
        long value = number.longValue();
        if (value < 0) {
            throw new InvalidControlArgumentException("参数 " + name + " 不能为负");
        }
        return value;
    }

    static int optionalInt(Map<String, Object> arguments, String name, int defaultValue,
                           int minimum, int maximum) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number) || !isIntegral(number)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是整数");
        }
        long value = number.longValue();
        if (value < minimum || value > maximum) {
            throw new InvalidControlArgumentException(
                    "参数 " + name + " 必须在 " + minimum + ".." + maximum + " 范围内");
        }
        return (int) value;
    }

    static int requiredInt(Map<String, Object> arguments, String name, int minimum, int maximum) {
        Object raw = arguments.get(name);
        if (!(raw instanceof Number number) || !isIntegral(number)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是整数");
        }
        long value = number.longValue();
        if (value < minimum || value > maximum) {
            throw new InvalidControlArgumentException(
                    "参数 " + name + " 必须在 " + minimum + ".." + maximum + " 范围内");
        }
        return (int) value;
    }

    static List<String> requiredTextList(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是非空字符串数组");
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new InvalidControlArgumentException("参数 " + name + " 不能包含空字符串");
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    static List<String> optionalTextList(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是字符串数组");
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new InvalidControlArgumentException("参数 " + name + " 不能包含空字符串");
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    static Map<String, String> requiredStringMap(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是字符串到字符串的对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()
                    || !(entry.getValue() instanceof String value) || value.isBlank()) {
                throw new InvalidControlArgumentException(
                        "参数 " + name + " 的键和值都必须是非空白字符串");
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    static List<Map<String, Object>> requiredObjectList(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (!(raw instanceof List<?> list)) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是对象数组");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new InvalidControlArgumentException("参数 " + name + " 的每一项必须是对象");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new InvalidControlArgumentException("参数 " + name + " 的对象键必须是字符串");
                }
                normalized.put(key, entry.getValue());
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long
                || number instanceof java.math.BigInteger;
    }
}
