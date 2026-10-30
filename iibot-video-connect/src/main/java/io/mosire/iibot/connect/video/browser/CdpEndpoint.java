package io.mosire.iibot.connect.video.browser;

import java.net.URI;
import java.util.Locale;

/**
 * Chrome DevTools Protocol 端点：只允许回环 host，避免把 CDP 暴露给远程网络。
 *
 * <p>错误消息不包含原始 endpoint，防止 query 中的临时 token 进入日志。</p>
 */
public record CdpEndpoint(URI uri) {
    public CdpEndpoint {
        if (uri == null) {
            throw new IllegalArgumentException("cdpEndpoint 不能为空");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)
                && !"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new IllegalArgumentException("cdpEndpoint 只允许 http/https/ws/wss");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("cdpEndpoint 必须是带 host 的合法 URI");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("cdpEndpoint 不允许包含 userinfo");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)
                && !"::1".equals(host) && !"0:0:0:0:0:0:0:1".equals(host)) {
            throw new IllegalArgumentException("cdpEndpoint 只允许回环地址");
        }
    }

    public static CdpEndpoint parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("cdpEndpoint 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cdpEndpoint 不是合法 URI");
        }
        return new CdpEndpoint(uri);
    }
}
