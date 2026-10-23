package io.mosire.iibot.taskflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 领域内用于预览、快照和提案完整性校验的 SHA-256 辅助方法。 */
public final class Hashes {
    private Hashes() {
    }

    public static String sha256(String canonicalText) {
        if (canonicalText == null) {
            throw new IllegalArgumentException("canonicalText 不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalText.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java 运行时必须提供 SHA-256", e);
        }
    }
}
