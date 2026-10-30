package io.mosire.iibot.connect.video.core;

import java.util.List;

/** 一次播放流选择的可审计结果。 */
public record VideoStreamInfo(
        String requestedQuality,
        int requestedMaxHeight,
        String actualQuality,
        int width,
        int height,
        List<Integer> availableHeights,
        boolean fallback) {

    public VideoStreamInfo {
        requestedQuality = requestedQuality == null ? "auto" : requestedQuality.trim();
        actualQuality = actualQuality == null ? "unknown" : actualQuality.trim();
        if (requestedMaxHeight < 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("视频流尺寸不能为负");
        }
        availableHeights = availableHeights == null
                ? List.of()
                : availableHeights.stream()
                        .filter(value -> value != null && value > 0)
                        .distinct()
                        .sorted()
                        .toList();
    }
}
