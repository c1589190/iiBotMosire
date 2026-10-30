package io.mosire.iibot.connect.video.browser;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 浏览器目标域名白名单；生产默认只允许 B 站及登录域名。 */
public record BrowserPolicy(Set<String> allowedHosts, boolean allowLoopbackHttp) {
    public BrowserPolicy {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        if (allowedHosts != null) {
            for (String host : allowedHosts) {
                if (host != null && !host.isBlank()) copy.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        allowedHosts = Set.copyOf(copy);
    }

    public static BrowserPolicy bilibili() {
        return new BrowserPolicy(Set.of(
                "bilibili.com", "www.bilibili.com", "m.bilibili.com",
                "player.bilibili.com", "api.bilibili.com",
                "passport.bilibili.com", "space.bilibili.com", "t.bilibili.com"), false);
    }

    /** 仅用于 fake 页面测试，生产代码不得使用。 */
    public static BrowserPolicy testLoopback() {
        return new BrowserPolicy(Set.of("127.0.0.1", "localhost"), true);
    }

    public boolean allows(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return allowedHosts.contains(host);
        if ("http".equals(scheme)) {
            return allowLoopbackHttp && ("127.0.0.1".equals(host) || "localhost".equals(host));
        }
        return false;
    }
}
