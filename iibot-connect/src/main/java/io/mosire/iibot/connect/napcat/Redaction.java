package io.mosire.iibot.connect.napcat;

import java.net.URI;

/** 日志与错误摘要脱敏工具。绝不返回 token 或完整查询串。 */
final class Redaction {

    private Redaction() {
    }

    static String errorSummary(Throwable throwable, String secret) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return name;
        }
        return name + ": " + sanitize(message, secret);
    }

    static String sanitize(String value, String secret) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value;
        if (secret != null && !secret.isBlank()) {
            sanitized = sanitized.replace(secret, "***");
        }
        sanitized = sanitized.replaceAll("(?i)(access_token=)[^&\\s]*", "$1***");
        sanitized = sanitized.replaceAll("\\?[^\\s]*", "?<redacted>");
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
        }
        return sanitized;
    }

    static String safeUri(URI uri) {
        if (uri == null) {
            return "<null>";
        }
        String scheme = uri.getScheme() == null ? "?" : uri.getScheme();
        String host = uri.getHost() == null ? "?" : uri.getHost();
        int port = uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path
                + (uri.getRawQuery() == null ? "" : "?<redacted>");
    }
}
