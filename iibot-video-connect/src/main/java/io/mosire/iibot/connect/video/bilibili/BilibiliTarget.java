package io.mosire.iibot.connect.video.bilibili;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析后的 B 站视频目标；bvid/aid 至少有一个。 */
public record BilibiliTarget(String bvid, String aid, int page) {

    private static final Pattern BVID = Pattern.compile("^BV[0-9A-Za-z]{10}$");
    private static final Pattern AID = Pattern.compile("^av(\\d{1,20})$",
            Pattern.CASE_INSENSITIVE);

    public BilibiliTarget {
        bvid = bvid == null ? "" : bvid.trim();
        aid = aid == null ? "" : aid.trim();
        if (bvid.isEmpty() && aid.isEmpty()) {
            throw new IllegalArgumentException("B站视频目标必须包含 bvid 或 aid");
        }
        if (!bvid.isEmpty() && !BVID.matcher(bvid).matches()) {
            throw new IllegalArgumentException("B站 BV 号格式非法");
        }
        if (!aid.isEmpty() && !aid.matches("\\d{1,20}")) {
            throw new IllegalArgumentException("B站 av 号格式非法");
        }
        if (page < 1 || page > 100) {
            throw new IllegalArgumentException("B站分P必须在 1..100");
        }
    }

    public static BilibiliTarget parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("B站视频目标为空");
        if (BVID.matcher(value).matches()) return new BilibiliTarget(value, "", 1);
        Matcher aid = AID.matcher(value);
        if (aid.matches()) return new BilibiliTarget("", aid.group(1), 1);

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("B站视频 URL 非法");
        }
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("只允许 http(s) B站视频 URL");
        }
        String host = uri.getHost() == null
                ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!isBilibiliHost(host)) {
            throw new IllegalArgumentException("不是 B站视频域名");
        }
        int page = queryPage(uri.getQuery());
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");
        for (int index = 0; index + 1 < segments.length; index++) {
            if (!"video".equalsIgnoreCase(segments[index])) continue;
            String id = segments[index + 1];
            if (BVID.matcher(id).matches()) return new BilibiliTarget(id, "", page);
            Matcher av = AID.matcher(id);
            if (av.matches()) return new BilibiliTarget("", av.group(1), page);
        }
        throw new IllegalArgumentException("B站 URL 中没有可用视频 BV/av 号");
    }

    public static boolean isBilibiliHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.equals("bilibili.com")
                || normalized.endsWith(".bilibili.com")
                || normalized.equals("b23.tv")
                || normalized.endsWith(".b23.tv")
                || normalized.equals("bili2233.cn")
                || normalized.endsWith(".bili2233.cn");
    }

    private static int queryPage(String query) {
        if (query == null || query.isBlank()) return 1;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals <= 0 || !"p".equals(part.substring(0, equals))) continue;
            try {
                int page = Integer.parseInt(part.substring(equals + 1));
                return Math.max(1, Math.min(100, page));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }
}
