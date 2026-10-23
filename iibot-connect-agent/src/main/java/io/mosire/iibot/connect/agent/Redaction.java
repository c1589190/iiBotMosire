package io.mosire.iibot.connect.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具输出脱敏工具。
 *
 * <p>适配器状态和回执中的错误文本属于外部输入，可能包含 access token、Authorization 头或完整 URL。
 * 本类在文本进入 {@code ToolResult} 前做一次保守清洗：完整 URL、Bearer 凭据与常见 secret 赋值全部替换为
 * 固定占位符，并限制长度，避免把底层协议对象或凭据带进模型上下文。</p>
 */
final class Redaction {

    private static final int MAX_LENGTH = 512;
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?)://[^\\s\"']+");
    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)\\b(authorization)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(access[_-]?token|token|api[_-]?key|apikey|password|passwd|secret|credential)"
                    + "\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\t]+");

    private Redaction() {
    }

    /**
     * 清洗一段可能含敏感信息的外部文本。
     *
     * @param value 原始文本；null 返回空串
     * @return 最长 512 字符的脱敏文本
     */
    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String result = URL.matcher(value).replaceAll("[URL已脱敏]");
        result = AUTHORIZATION.matcher(result).replaceAll("$1=***");
        result = BEARER.matcher(result).replaceAll("$1 ***");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=***");
        result = LINE_BREAKS.matcher(result).replaceAll(" ").trim();
        if (result.length() <= MAX_LENGTH) {
            return result;
        }
        return result.substring(0, MAX_LENGTH) + "…";
    }

    /**
     * 清洗稳定错误码。错误码只用于机器判断；本方法仍做长度与字符白名单收口。
     *
     * @param value 原始错误码；null 返回空串
     * @return 只含字母、数字、下划线、短横线与点的稳定文本
     */
    static String sanitizeCode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.length() <= 96 ? sanitized : sanitized.substring(0, 96);
    }

    /** 判断文本中是否仍含明显 URL 或 secret 赋值（测试与防御性断言用）。 */
    static boolean containsSensitivePattern(String value) {
        if (value == null) {
            return false;
        }
        Matcher url = URL.matcher(value);
        Matcher secret = SECRET_ASSIGNMENT.matcher(value);
        Matcher authorization = AUTHORIZATION.matcher(value);
        return url.find() || secret.find() || authorization.find();
    }
}
