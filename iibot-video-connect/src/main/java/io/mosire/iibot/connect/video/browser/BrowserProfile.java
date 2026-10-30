package io.mosire.iibot.connect.video.browser;

import java.nio.file.Path;
import java.util.Objects;

/** 浏览器会话参数；不包含 Agent 可传的任意 URL 或 JavaScript。 */
public record BrowserProfile(
        String site,
        String accountRef,
        boolean headless,
        int viewportWidth,
        int viewportHeight,
        String locale,
        int timeoutSeconds,
        Path profileDir) {

    public BrowserProfile {
        site = site == null || site.isBlank() ? "bilibili" : site.trim();
        accountRef = accountRef == null ? "" : accountRef.trim();
        if (viewportWidth < 320 || viewportWidth > 3840) {
            throw new IllegalArgumentException("browser viewportWidth 必须在 320..3840");
        }
        if (viewportHeight < 240 || viewportHeight > 2160) {
            throw new IllegalArgumentException("browser viewportHeight 必须在 240..2160");
        }
        locale = locale == null || locale.isBlank() ? "zh-CN" : locale.trim();
        if (timeoutSeconds < 5 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("browser timeoutSeconds 必须在 5..300");
        }
        profileDir = Objects.requireNonNull(profileDir, "profileDir").toAbsolutePath().normalize();
    }

    public static BrowserProfile bilibili(boolean headless, int width, int height,
                                          int timeoutSeconds, Path profileDir) {
        return new BrowserProfile("bilibili", "", headless, width, height,
                "zh-CN", timeoutSeconds, profileDir);
    }
}
