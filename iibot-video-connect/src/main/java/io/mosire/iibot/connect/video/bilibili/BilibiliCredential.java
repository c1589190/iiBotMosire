package io.mosire.iibot.connect.video.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 本机 B 站登录凭据。只从 0600 文件读取；{@link #toString()} 永不输出值。
 */
public record BilibiliCredential(String sessdata, String biliJct, String buvid3,
                                 String buvid4, String dedeUserId) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_FILE_BYTES = 16 * 1024L;

    public BilibiliCredential {
        sessdata = optional(sessdata);
        biliJct = optional(biliJct);
        buvid3 = optional(buvid3);
        buvid4 = optional(buvid4);
        dedeUserId = optional(dedeUserId);
        if (sessdata.isEmpty() != biliJct.isEmpty()) {
            throw new IllegalArgumentException(
                    "sessdata 与 biliJct 必须同时为空或同时提供");
        }
    }

    /** 匿名只读客户端；不能用于需要登录态的 Adapter 写操作。 */
    public static BilibiliCredential anonymous() {
        return new BilibiliCredential("", "", "", "", "");
    }

    public boolean authenticated() {
        return !sessdata.isEmpty();
    }

    public static BilibiliCredential load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("B站凭据文件不存在");
        }
        if (Files.size(absolute) > MAX_FILE_BYTES) {
            throw new IOException("B站凭据文件过大");
        }
        enforceOwnerOnly(absolute);
        JsonNode root = JSON.readTree(Files.readString(absolute));
        if (root == null || !root.isObject()) {
            throw new IOException("B站凭据文件必须是 JSON object");
        }
        String sessdata = firstText(root, "sessdata", "SESSDATA");
        String biliJct = firstText(root, "biliJct", "bili_jct", "BILI_JCT");
        String buvid3 = firstText(root, "buvid3", "BUVID3");
        String buvid4 = firstText(root, "buvid4", "BUVID4");
        String uid = firstText(root, "dedeUserId", "dedeuserid", "DedeUserID",
                "DEDEUSERID");
        if (sessdata.isBlank() || biliJct.isBlank()) {
            throw new IOException("B站凭据缺少 SESSDATA 或 bili_jct");
        }
        return new BilibiliCredential(sessdata, biliJct, buvid3, buvid4, uid);
    }

    public String cookieHeader() {
        if (!authenticated()) return "";
        StringBuilder cookie = new StringBuilder("SESSDATA=").append(sessdata);
        cookie.append("; bili_jct=").append(biliJct);
        if (!buvid3.isBlank()) cookie.append("; buvid3=").append(buvid3);
        if (!buvid4.isBlank()) cookie.append("; buvid4=").append(buvid4);
        if (!dedeUserId.isBlank()) cookie.append("; DedeUserID=").append(dedeUserId);
        return cookie.toString();
    }

    public boolean hasDeviceId() {
        return !buvid3.isBlank() || !buvid4.isBlank();
    }

    @Override
    public String toString() {
        return "BilibiliCredential[redacted]";
    }

    private static void enforceOwnerOnly(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.stream().anyMatch(permission ->
                    permission == PosixFilePermission.GROUP_READ
                            || permission == PosixFilePermission.GROUP_WRITE
                            || permission == PosixFilePermission.GROUP_EXECUTE
                            || permission == PosixFilePermission.OTHERS_READ
                            || permission == PosixFilePermission.OTHERS_WRITE
                            || permission == PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IOException("B站凭据文件权限必须是 0600");
            }
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统由部署方保证权限。
        }
    }

    private static String firstText(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && node.isTextual()) {
                String value = node.asText();
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return "";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
