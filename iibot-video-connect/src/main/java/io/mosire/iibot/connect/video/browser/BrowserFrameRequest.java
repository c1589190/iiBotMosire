package io.mosire.iibot.connect.video.browser;

import java.nio.file.Path;
import java.util.Objects;

/** 只描述 B 站视频分P与时间点，不接受任意页面地址。 */
public record BrowserFrameRequest(
        String bvid,
        int page,
        double timestampSeconds,
        String selector,
        Path outputFile) {

    public BrowserFrameRequest {
        bvid = Objects.requireNonNull(bvid, "bvid").trim();
        if (!bvid.matches("BV[0-9A-Za-z]{10}")) {
            throw new IllegalArgumentException("bvid 格式不合法");
        }
        if (page < 1 || page > 100) {
            throw new IllegalArgumentException("page 必须在 1..100");
        }
        if (!Double.isFinite(timestampSeconds) || timestampSeconds < 0) {
            throw new IllegalArgumentException("timestampSeconds 必须为非负有限数");
        }
        selector = selector == null || selector.isBlank() ? "video" : selector.trim();
        if (selector.length() > 200) {
            throw new IllegalArgumentException("selector 过长");
        }
        outputFile = Objects.requireNonNull(outputFile, "outputFile").toAbsolutePath().normalize();
    }

    public BrowserFrameRequest(String bvid, int page, double timestampSeconds, Path outputFile) {
        this(bvid, page, timestampSeconds, "video", outputFile);
    }
}
