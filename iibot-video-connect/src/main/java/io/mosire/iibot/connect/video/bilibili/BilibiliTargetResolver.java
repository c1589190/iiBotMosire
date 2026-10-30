package io.mosire.iibot.connect.video.bilibili;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/** 展开 b23.tv 短链；只允许跳转到 B 站域名，最多 5 跳。 */
public final class BilibiliTargetResolver {

    private static final int MAX_HOPS = 5;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public BilibiliTarget resolve(String raw) {
        IllegalArgumentException directFailure = null;
        try {
            return BilibiliTarget.parse(raw);
        } catch (IllegalArgumentException invalid) {
            directFailure = invalid;
        }
        URI current;
        try {
            current = URI.create(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw directFailure == null ? invalid : directFailure;
        }
        String host = current.getHost() == null
                ? "" : current.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("b23.tv") || host.endsWith(".b23.tv")
                || host.equals("bili2233.cn") || host.endsWith(".bili2233.cn"))) {
            throw directFailure == null
                    ? new IllegalArgumentException("B站视频目标非法") : directFailure;
        }
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!isHttp(current)) {
                throw new IllegalArgumentException("B站短链只允许 http(s)");
            }
            if (!BilibiliTarget.isBilibiliHost(current.getHost())) {
                throw new IllegalArgumentException("B站短链跳转域名非法");
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<Void> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("B站短链解析被中断");
            } catch (IOException failure) {
                throw new IllegalArgumentException("B站短链解析失败");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse("");
                if (location.isBlank()) {
                    throw new IllegalArgumentException("B站短链缺少跳转地址");
                }
                current = current.resolve(location);
                BilibiliTarget parsed = tryParse(current.toString());
                if (parsed != null) return parsed;
                continue;
            }
            throw new IllegalArgumentException("B站短链没有指向视频页面");
        }
        throw new IllegalArgumentException("B站短链跳转次数过多");
    }

    private static BilibiliTarget tryParse(String raw) {
        try {
            return BilibiliTarget.parse(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }
}
