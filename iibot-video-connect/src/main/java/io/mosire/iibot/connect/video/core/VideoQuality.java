package io.mosire.iibot.connect.video.core;

import java.util.Locale;

/** 视频清晰度偏好；高度是选择目标，不是上游必定满足的承诺。 */
public enum VideoQuality {
    AUTO("auto", 0, 64),
    P360("360p", 360, 16),
    P480("480p", 480, 32),
    P720("720p", 720, 64),
    P1080("1080p", 1080, 80),
    BEST("best", 0, 120);

    private final String wireName;
    private final int targetHeight;
    private final int qn;

    VideoQuality(String wireName, int targetHeight, int qn) {
        this.wireName = wireName;
        this.targetHeight = targetHeight;
        this.qn = qn;
    }

    public String wireName() {
        return wireName;
    }

    /** 显式清晰度对应的像素高度；auto/best 返回 0。 */
    public int targetHeight() {
        return targetHeight;
    }

    /** B 站 playurl 的 qn 参数。auto 由调用方按 maxHeight 计算。 */
    public int qn() {
        return qn;
    }

    public boolean explicitHeight() {
        return this != AUTO && this != BEST;
    }

    public static VideoQuality parse(String value) {
        if (value == null || value.isBlank()) return AUTO;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (VideoQuality quality : values()) {
            if (quality.wireName.equals(normalized)) return quality;
        }
        throw new IllegalArgumentException(
                "quality 只允许 auto/360p/480p/720p/1080p/best");
    }

    public static String labelForHeight(int height) {
        if (height <= 0) return "unknown";
        return switch (height) {
            case 360 -> "360p";
            case 480 -> "480p";
            case 720 -> "720p";
            case 1080 -> "1080p";
            default -> height + "p";
        };
    }

    public static int qnForHeight(int height) {
        if (height <= 360) return 16;
        if (height <= 480) return 32;
        if (height <= 720) return 64;
        if (height <= 1080) return 80;
        return 120;
    }
}
