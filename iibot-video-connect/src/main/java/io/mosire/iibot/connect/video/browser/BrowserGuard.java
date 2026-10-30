package io.mosire.iibot.connect.video.browser;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** 拒绝 file/data/javascript 与非白名单域名；不提供任意导航能力。 */
public final class BrowserGuard {
    private final BrowserPolicy policy;

    public BrowserGuard(BrowserPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BrowserException("BROWSER_URL_INVALID", "浏览器目标为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new BrowserException("BROWSER_URL_INVALID", "浏览器目标不是合法 URI", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BrowserException("BROWSER_SCHEME_DENIED", "浏览器只允许 http/https");
        }
        if (!policy.allows(uri)) {
            throw new BrowserException("BROWSER_DOMAIN_DENIED", "浏览器目标域名不在白名单");
        }
        return uri;
    }
}
