package io.mosire.iibot.taskflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * 面向玩家的短确认码。
 *
 * <p>短码由实体类型 + 稳定实体 ID 派生，只用于人机确认的显示与查找；真实授权仍必须
 * 回查当前玩家、当前回合/工单、状态与 revision。短码不是安全凭据，不能替代宿主校验。</p>
 */
public final class ConfirmationCodes {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;

    private ConfirmationCodes() {
    }

    public static String forDecisionDraft(String draftId) {
        return of("decision:" + Objects.requireNonNull(draftId, "draftId"));
    }

    public static String forActionTicket(String ticketId) {
        return of("ticket:" + Objects.requireNonNull(ticketId, "ticketId"));
    }

    public static String of(String stableValue) {
        byte[] hash = sha256(stableValue);
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[hash[i] & 0x1f]);
        }
        return code.toString();
    }

    public static boolean looksLikeShortCode(String value) {
        if (value == null || value.length() != LENGTH) {
            return false;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            if (upper.charAt(i) < '0' || upper.charAt(i) > 'Z') {
                return false;
            }
        }
        return true;
    }

    public static boolean matches(String candidate, String expected) {
        return candidate != null && expected != null
                && candidate.trim().equalsIgnoreCase(expected);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
