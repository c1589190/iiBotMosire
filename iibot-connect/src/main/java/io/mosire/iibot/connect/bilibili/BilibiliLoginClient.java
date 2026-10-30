package io.mosire.iibot.connect.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * B 站扫码登录最小客户端。只返回二维码 URL/状态和最终凭据；日志不得打印 Cookie。
 */
public final class BilibiliLoginClient implements AutoCloseable {

    private static final String DEFAULT_BASE = "https://passport.bilibili.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final URI generateUri;
    private final URI pollUri;

    public BilibiliLoginClient() {
        this(DEFAULT_BASE);
    }

    /** 允许测试指向本地 fake passport 服务。 */
    public BilibiliLoginClient(String baseUrl) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl").trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!(base.startsWith("http://") || base.startsWith("https://"))) {
            throw new IllegalArgumentException("登录 baseUrl 必须是 http(s)");
        }
        this.generateUri = URI.create(base
                + "/x/passport-login/web/qrcode/generate");
        this.pollUri = URI.create(base + "/x/passport-login/web/qrcode/poll");
    }

    public QrStart start() {
        JsonNode root = get(generateUri, "BILI_LOGIN_GENERATE_FAILED");
        JsonNode data = root.path("data");
        String url = text(data.get("url"));
        String key = text(data.get("qrcode_key"));
        if (url.isBlank() || key.isBlank()) {
            throw new BilibiliHttpClient.BilibiliApiException(
                    "BILI_LOGIN_QR_INVALID", "B站没有返回有效二维码");
        }
        return new QrStart(url, key);
    }

    public QrPoll poll(String qrcodeKey) {
        if (qrcodeKey == null || qrcodeKey.isBlank()) {
            throw new IllegalArgumentException("qrcodeKey 不能为空");
        }
        URI uri = URI.create(pollUri + "?qrcode_key="
                + URLEncoder.encode(qrcodeKey, StandardCharsets.UTF_8));
        Response response = getResponse(uri, "BILI_LOGIN_POLL_FAILED");
        JsonNode root = response.json();
        JsonNode data = root.path("data");
        int loginCode = intValue(data.get("code"));
        String message = firstNonBlank(text(data.get("message")),
                text(root.get("message")));
        return switch (loginCode) {
            case 0 -> {
                Map<String, String> cookies = parseCookies(response.setCookies());
                mergeQueryCookies(cookies, text(data.get("url")));
                String sessdata = cookies.getOrDefault("SESSDATA", "");
                String biliJct = cookies.getOrDefault("bili_jct", "");
                if (sessdata.isBlank() || biliJct.isBlank()) {
                    yield new QrPoll(QrState.FAILED, "B站登录成功但 Cookie 不完整", null);
                }
                yield new QrPoll(QrState.SUCCESS, "B站登录成功",
                        new BilibiliCredential(sessdata, biliJct,
                                cookies.getOrDefault("buvid3", ""),
                                cookies.getOrDefault("buvid4", ""),
                                cookies.getOrDefault("DedeUserID", "")));
            }
            case 86101 -> new QrPoll(QrState.WAITING_SCAN, "等待扫码", null);
            case 86090 -> new QrPoll(QrState.SCANNED, "已扫码，等待手机确认", null);
            case 86038 -> new QrPoll(QrState.EXPIRED, "二维码已失效", null);
            default -> new QrPoll(QrState.FAILED,
                    message.isBlank() ? "B站登录失败" : message, null);
        };
    }

    @Override
    public void close() {
        http.close();
    }

    private JsonNode get(URI uri, String errorCode) {
        return getResponse(uri, errorCode).json();
    }

    private Response getResponse(URI uri, String errorCode) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Cookie", "SESSDATA=")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BilibiliHttpClient.BilibiliTransportException(
                    "BILI_LOGIN_INTERRUPTED", "B站登录请求被中断");
        } catch (IOException failure) {
            throw new BilibiliHttpClient.BilibiliTransportException(
                    "BILI_LOGIN_IO", "B站登录网络请求失败");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BilibiliHttpClient.BilibiliApiException(errorCode,
                    "B站登录 HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (IOException invalid) {
            throw new BilibiliHttpClient.BilibiliTransportException(
                    "BILI_LOGIN_INVALID_JSON", "B站登录响应不是有效 JSON");
        }
        int code = intValue(root.get("code"));
        if (code != 0) {
            throw new BilibiliHttpClient.BilibiliApiException(
                    "BILI_LOGIN_CODE_" + code,
                    firstNonBlank(text(root.get("message")), "B站登录接口拒绝"));
        }
        return new Response(root, response.headers().allValues("set-cookie"));
    }

    private static Map<String, String> parseCookies(List<String> headers) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : headers) {
            if (header == null) continue;
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            cookies.put(pair.substring(0, equals).trim(),
                    pair.substring(equals + 1).trim());
        }
        return cookies;
    }

    private static void mergeQueryCookies(Map<String, String> cookies, String url) {
        if (url == null || url.isBlank()) return;
        int question = url.indexOf('?');
        if (question < 0) return;
        for (String part : url.substring(question + 1).split("&")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = URLDecoder.decode(part.substring(0, equals),
                    StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(equals + 1),
                    StandardCharsets.UTF_8);
            if (value.isBlank()) continue;
            if ("SESSDATA".equalsIgnoreCase(key)) cookies.put("SESSDATA", value);
            if ("bili_jct".equalsIgnoreCase(key)) cookies.put("bili_jct", value);
            if ("DedeUserID".equalsIgnoreCase(key)) cookies.put("DedeUserID", value);
            if ("buvid3".equalsIgnoreCase(key)) cookies.put("buvid3", value);
            if ("buvid4".equalsIgnoreCase(key)) cookies.put("buvid4", value);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static int intValue(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        return node.isNumber() ? node.asInt() : parseInt(node.asText("0"));
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    public record QrStart(String url, String qrcodeKey) {
        public QrStart {
            url = Objects.requireNonNull(url, "url");
            qrcodeKey = Objects.requireNonNull(qrcodeKey, "qrcodeKey");
        }
    }

    public enum QrState {
        WAITING_SCAN,
        SCANNED,
        EXPIRED,
        SUCCESS,
        FAILED
    }

    public record QrPoll(QrState state, String message, BilibiliCredential credential) {
        public QrPoll {
            state = Objects.requireNonNull(state, "state");
            message = message == null ? "" : message;
        }
    }

    private record Response(JsonNode json, List<String> setCookies) {
    }
}
