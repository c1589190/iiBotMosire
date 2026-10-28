package io.mosire.iibot.app.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 配置装载失败的安全异常。
 *
 * <p>消息只包含来源文件、JSON pointer、期望/实际类型和可操作提示，绝不包含环境变量值、
 * 访问令牌或 API key。启动路径必须把它视为 fail-closed。</p>
 */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String file;
    private final String jsonPointer;
    private final String expected;
    private final String actualType;
    private final String hint;

    public ConfigException(String message) {
        this(null, null, null, null, message, null);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
        this.file = null;
        this.jsonPointer = null;
        this.expected = null;
        this.actualType = null;
        this.hint = message;
    }

    private ConfigException(String file, String jsonPointer, String expected,
                            String actualType, String hint, Throwable cause) {
        super(format(file, jsonPointer, expected, actualType, hint), cause);
        this.file = file;
        this.jsonPointer = jsonPointer;
        this.expected = expected;
        this.actualType = actualType;
        this.hint = hint;
    }

    /** 构造带来源定位的配置错误；所有字段均为脱敏后的结构/类型信息。 */
    public static ConfigException invalid(Path file, String jsonPointer, String expected,
                                          String actualType, String hint) {
        return new ConfigException(file == null ? null : file.toString(),
                normalizePointer(jsonPointer), expected, actualType, hint, null);
    }

    public static ConfigException invalid(Path file, String jsonPointer, String expected,
                                          String actualType, String hint, Throwable cause) {
        return new ConfigException(file == null ? null : file.toString(),
                normalizePointer(jsonPointer), expected, actualType, hint, cause);
    }

    public String file() {
        return file;
    }

    public String jsonPointer() {
        return jsonPointer;
    }

    public String expected() {
        return expected;
    }

    public String actualType() {
        return actualType;
    }

    public String hint() {
        return hint;
    }

    private static String normalizePointer(String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return "/";
        }
        String normalized = pointer.startsWith("/") ? pointer : "/" + pointer;
        while (normalized.startsWith("//")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String format(String file, String pointer, String expected,
                                 String actualType, String hint) {
        return "CONFIG_INVALID\n"
                + "file: " + (file == null || file.isBlank() ? "<unknown>" : file) + "\n"
                + "jsonPointer: " + normalizePointer(pointer) + "\n"
                + "expected: " + Objects.toString(expected, "<unspecified>") + "\n"
                + "actual: " + Objects.toString(actualType, "<unspecified>") + "\n"
                + "hint: " + Objects.toString(hint, "<none>");
    }
}
