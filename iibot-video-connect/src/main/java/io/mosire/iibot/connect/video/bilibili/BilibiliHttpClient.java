package io.mosire.iibot.connect.video.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.connect.video.core.VideoQuality;
import io.mosire.iibot.connect.video.core.VideoStreamInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 最小 Bilibili Web API 客户端：登录态、WBI、私信读取与发送。
 *
 * <p>只读取需要的字段；异常消息和日志不得包含 Cookie、签名 query 或完整响应正文。</p>
 */
public final class BilibiliHttpClient implements AutoCloseable {

    public static final String API_BASE = "https://api.bilibili.com";
    public static final String VC_API_BASE = "https://api.vc.bilibili.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration WBI_TTL = Duration.ofHours(12);
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/155.0.0.0 Safari/537.36";
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final HttpClient mediaHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final BilibiliCredential credential;
    private final String userAgent;
    private final String deviceId;
    private final URI navUri;
    private final URI viewUri;
    private final URI repliesUri;
    private final URI replyReplyUri;
    private final URI playerWbiUri;
    private final URI playUrlUri;
    private final URI msgfeedReplyUri;
    private final URI newSessionsUri;
    private final URI fetchSessionMessagesUri;
    private final URI updateAckUri;
    private final URI sendMessageUri;
    private final URI replyAddUri;
    private volatile String imgKey = "";
    private volatile String subKey = "";
    private volatile Instant wbiExpiresAt = Instant.EPOCH;
    private volatile BilibiliModels.AccountInfo accountInfo;

    public BilibiliHttpClient(BilibiliCredential credential) {
        this(credential, DEFAULT_UA, API_BASE, VC_API_BASE);
    }

    public BilibiliHttpClient(BilibiliCredential credential, String userAgent) {
        this(credential, userAgent, API_BASE, VC_API_BASE);
    }

    /** 允许测试指向本地 fake B 站服务；生产调用使用上面的默认构造函数。 */
    public BilibiliHttpClient(BilibiliCredential credential, String userAgent,
                              String apiBase, String vcApiBase) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? DEFAULT_UA : userAgent.trim();
        String api = requireBaseUrl(apiBase, "apiBase");
        String vc = requireBaseUrl(vcApiBase, "vcApiBase");
        this.navUri = URI.create(api + "/x/web-interface/nav");
        this.viewUri = URI.create(api + "/x/web-interface/view");
        this.repliesUri = URI.create(api + "/x/v2/reply");
        this.replyReplyUri = URI.create(api + "/x/v2/reply/reply");
        this.playerWbiUri = URI.create(api + "/x/player/wbi/v2");
        this.playUrlUri = URI.create(api + "/x/player/playurl");
        this.msgfeedReplyUri = URI.create(api + "/x/msgfeed/reply");
        this.replyAddUri = URI.create(api + "/x/v2/reply/add");
        this.newSessionsUri = URI.create(vc
                + "/session_svr/v1/session_svr/new_sessions");
        this.fetchSessionMessagesUri = URI.create(vc
                + "/svr_sync/v1/svr_sync/fetch_session_msgs");
        this.updateAckUri = URI.create(vc
                + "/session_svr/v1/session_svr/update_ack");
        this.sendMessageUri = URI.create(vc + "/web_im/v1/web_im/send_msg");
        String seed = credential.dedeUserId().isBlank()
                ? credential.sessdata() : credential.dedeUserId();
        this.deviceId = UUID.nameUUIDFromBytes(
                ("iibot-bili:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public synchronized BilibiliModels.AccountInfo validateAccount() {
        if (accountInfo != null) return accountInfo;
        JsonNode root = getJson(navUri, true, "BILI_AUTH_CHECK_FAILED");
        JsonNode data = root.path("data");
        if (!data.path("isLogin").asBoolean(false)) {
            throw new BilibiliApiException("BILI_AUTH_REQUIRED", "B站账号未登录或登录已失效");
        }
        long mid = longValue(data.get("mid"));
        String uname = text(data.get("uname"));
        if (mid <= 0L) {
            throw new BilibiliApiException("BILI_AUTH_INVALID", "B站登录态缺少有效 UID");
        }
        if (!credential.dedeUserId().isBlank()) {
            long expected;
            try {
                expected = Long.parseLong(credential.dedeUserId());
            } catch (NumberFormatException invalid) {
                throw new BilibiliApiException("BILI_CREDENTIAL_INVALID",
                        "B站凭据 DedeUserID 非法");
            }
            if (expected != mid) {
                throw new BilibiliApiException("BILI_ACCOUNT_MISMATCH",
                        "B站凭据 UID 与登录态不一致");
            }
        }
        accountInfo = new BilibiliModels.AccountInfo(mid, uname);
        return accountInfo;
    }

    public BilibiliModels.VideoInfo video(String bvid, String aid, int requestedPage) {
        Map<String, String> query = new LinkedHashMap<>();
        if (bvid != null && !bvid.isBlank()) query.put("bvid", bvid.trim());
        else if (aid != null && !aid.isBlank()) query.put("aid", aid.trim());
        else throw new IllegalArgumentException("bvid/aid 至少提供一个");
        JsonNode root = getJson(withQuery(viewUri, query), false, "BILI_VIDEO_FAILED");
        JsonNode data = root.path("data");
        String resolvedBvid = text(data.get("bvid"));
        long resolvedAid = longValue(data.get("aid"));
        JsonNode pages = data.path("pages");
        int pageCount = pages.isArray() && !pages.isEmpty() ? pages.size() : 1;
        int page = Math.max(1, Math.min(requestedPage, pageCount));
        long cid;
        long pageDuration = longValue(data.get("duration"));
        if (pages.isArray() && !pages.isEmpty()) {
            cid = longValue(pages.get(page - 1).get("cid"));
            long candidate = longValue(pages.get(page - 1).get("duration"));
            if (candidate > 0L) pageDuration = candidate;
        } else {
            cid = longValue(data.get("cid"));
        }
        return new BilibiliModels.VideoInfo(
                resolvedBvid, resolvedAid, cid,
                text(data.get("title")), text(data.get("desc")),
                text(data.path("owner").get("name")),
                text(data.path("owner").get("mid")),
                longValue(data.get("duration")), pageDuration,
                pageCount, page);
    }

    public List<BilibiliModels.Comment> comments(String aid, int page, int size) {
        return commentPage(aid, "", page, size, "1").comments();
    }

    /** 顶层评论或楼中楼分页；rootRpid 为空时查询顶层评论。 */
    public BilibiliModels.CommentPage commentPage(String aid, String rootRpid,
                                                   int page, int size, String sort) {
        if (aid == null || aid.isBlank()) throw new IllegalArgumentException("aid 不能为空");
        if (page < 1 || page > 10_000) throw new IllegalArgumentException("comment page 超范围");
        if (size < 1 || size > 20) throw new IllegalArgumentException("comment size 超范围");
        String root = rootRpid == null ? "" : rootRpid.trim();
        if (!root.isEmpty() && !root.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("root 必须是评论 rpid");
        }
        String normalizedSort = sort == null || sort.isBlank() ? "1" : sort.trim();
        if (!Set.of("0", "1", "2").contains(normalizedSort)) {
            throw new IllegalArgumentException("comment sort 只允许 0/1/2");
        }
        URI endpoint = root.isEmpty() ? repliesUri : replyReplyUri;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("type", "1");
        query.put("oid", aid.trim());
        query.put("pn", Integer.toString(page));
        query.put("ps", Integer.toString(size));
        if (root.isEmpty()) {
            query.put("sort", normalizedSort);
            query.put("nohot", "0");
        } else {
            query.put("root", root);
        }
        JsonNode rootNode = getJson(withQuery(endpoint, query), false,
                "BILI_COMMENTS_FAILED");
        JsonNode data = rootNode.path("data");
        JsonNode replies = data.path("replies");
        List<BilibiliModels.Comment> values = new ArrayList<>();
        if (replies.isArray()) {
            for (JsonNode reply : replies) {
                String rpid = firstText(reply.get("rpid_str"), reply.get("rpid"));
                String rootId = firstText(reply.get("root_str"), reply.get("root"));
                String parentId = firstText(reply.get("parent_str"), reply.get("parent"));
                String message = text(reply.path("content").get("message"));
                if (rpid.isBlank() || message.isBlank()) continue;
                String resolvedRoot = !root.isEmpty()
                        ? root
                        : ("0".equals(rootId) ? rpid : rootId);
                values.add(new BilibiliModels.Comment(
                        rpid,
                        resolvedRoot,
                        "0".equals(parentId) ? rpid : parentId,
                        longValue(reply.path("member").get("mid")),
                        text(reply.path("member").get("uname")),
                        message,
                        longValue(reply.get("like")),
                        longValue(reply.get("ctime")),
                        intValue(reply.get("state"))));
            }
        }
        JsonNode pageNode = data.path("page");
        int currentPage = intValue(pageNode.get("num"));
        if (currentPage < 1) currentPage = page;
        int totalPages = intValue(pageNode.get("count"));
        long total = longValue(pageNode.get("acount"));
        if (total <= 0L) total = longValue(data.path("total"));
        boolean hasMore = totalPages > 0
                ? currentPage < totalPages
                : values.size() >= size;
        String nextCursor = hasMore ? Integer.toString(currentPage + 1) : "";
        return new BilibiliModels.CommentPage(values, currentPage, size, total,
                totalPages, hasMore, nextCursor);
    }

    public void downloadStream(BilibiliModels.PlayStream stream, java.nio.file.Path target,
                               long maxBytes) {
        if (stream == null) throw new IllegalArgumentException("stream 不能为空");
        if (stream.expectedBytes() > maxBytes) {
            throw new BilibiliApiException("BILI_STREAM_TOO_LARGE",
                    "B站视频流超过大小上限");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(stream.preferredUrl());
        } catch (IllegalArgumentException invalid) {
            throw new BilibiliApiException("BILI_STREAM_URL_INVALID", "B站视频流地址非法");
        }
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", userAgent)
                .header("Referer", "https://www.bilibili.com/")
                .GET()
                .build();
        try {
            java.net.http.HttpResponse<java.io.InputStream> response = mediaHttp.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BilibiliApiException("BILI_STREAM_HTTP_" + response.statusCode(),
                        "B站视频流下载失败");
            }
            long contentLength = response.headers().firstValueAsLong("content-length")
                    .orElse(0L);
            if (contentLength > maxBytes) {
                throw new BilibiliApiException("BILI_STREAM_TOO_LARGE",
                        "B站视频流超过大小上限");
            }
            try (java.io.InputStream input = response.body();
                 java.io.OutputStream output = java.nio.file.Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxBytes) {
                        throw new BilibiliApiException("BILI_STREAM_TOO_LARGE",
                                "B站视频流超过大小上限");
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BilibiliTransportException("BILI_STREAM_INTERRUPTED",
                    "B站视频流下载被中断");
        } catch (IOException failure) {
            throw new BilibiliTransportException("BILI_STREAM_IO",
                    "B站视频流下载失败");
        }
    }

    /** 选择不超过 maxHeight 的 DASH 视频流；只有 durl 时回退到整段 MP4。 */
    public BilibiliModels.PlayStream playStream(String bvid, long cid, int maxHeight) {
        return playStreamSelection(bvid, cid, VideoQuality.AUTO, false, maxHeight).stream();
    }

    /**
     * 选择播放流并返回 requested/actual/available/fallback 信息。
     *
     * <p>DASH 流按像素高度选择；只有 durl 时回退整段 MP4。strictQuality 只对
     * 显式 360p/480p/720p/1080p 生效：没有精确高度时抛出 QUALITY_UNAVAILABLE。</p>
     */
    public BilibiliModels.StreamSelection playStreamSelection(
            String bvid, long cid, VideoQuality quality, boolean strictQuality,
            int maxHeight) {
        if (bvid == null || bvid.isBlank() || cid <= 0L) {
            throw new IllegalArgumentException("bvid/cid 不能为空");
        }
        VideoQuality requested = quality == null ? VideoQuality.AUTO : quality;
        int heightLimit = Math.max(144, Math.min(maxHeight <= 0 ? 1080 : maxHeight, 2160));
        int qn = requested == VideoQuality.AUTO
                ? VideoQuality.qnForHeight(heightLimit) : requested.qn();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("bvid", bvid.trim());
        query.put("cid", Long.toString(cid));
        query.put("qn", Integer.toString(qn));
        query.put("fnval", "16");
        query.put("fourk", "1");
        query.put("platform", "html5");
        JsonNode root = getJson(withQuery(playUrlUri, query), credential.authenticated(),
                "BILI_PLAYURL_FAILED");
        JsonNode data = root.path("data");
        JsonNode videos = data.path("dash").path("video");
        if (videos.isArray() && !videos.isEmpty()) {
            List<Integer> available = new ArrayList<>();
            for (JsonNode video : videos) {
                int height = intValue(video.get("height"));
                if (height > 0 && !available.contains(height)) available.add(height);
            }
            available.sort(Integer::compareTo);
            JsonNode selected = selectStream(videos, requested, strictQuality, heightLimit);
            int selectedHeight = intValue(selected.get("height"));
            boolean fallback = requested == VideoQuality.AUTO
                    ? selectedHeight > heightLimit
                    : requested.explicitHeight() && selectedHeight != requested.targetHeight();
            String base = text(selected.get("baseUrl"));
            String backup = "";
            JsonNode backups = selected.path("backupUrl");
            if (backups.isArray() && !backups.isEmpty()) backup = text(backups.get(0));
            BilibiliModels.PlayStream stream = new BilibiliModels.PlayStream(base, backup,
                    intValue(selected.get("width")), selectedHeight,
                    text(selected.get("codecs")), longValue(selected.get("bandwidth")),
                    0L);
            VideoStreamInfo info = new VideoStreamInfo(
                    requested.wireName(), heightLimit,
                    VideoQuality.labelForHeight(selectedHeight), stream.width(),
                    stream.height(), available, fallback);
            return new BilibiliModels.StreamSelection(stream, info);
        }
        JsonNode durl = data.path("durl");
        if (durl.isArray() && !durl.isEmpty()) {
            if (strictQuality && requested.explicitHeight()) {
                throw new BilibiliApiException("QUALITY_UNAVAILABLE",
                        "B站没有返回指定清晰度的 DASH 流");
            }
            String url = text(durl.get(0).get("url"));
            String backup = "";
            JsonNode backups = durl.get(0).path("backup_url");
            if (backups.isArray() && !backups.isEmpty()) backup = text(backups.get(0));
            BilibiliModels.PlayStream stream = new BilibiliModels.PlayStream(
                    url, backup, 0, 0, "mp4", 0L, longValue(durl.get(0).get("size")));
            VideoStreamInfo info = new VideoStreamInfo(
                    requested.wireName(), heightLimit, "unknown", 0, 0,
                    List.of(), requested.explicitHeight());
            return new BilibiliModels.StreamSelection(stream, info);
        }
        throw new BilibiliApiException("BILI_PLAYURL_EMPTY", "B站没有返回可下载视频流");
    }

    private static JsonNode selectStream(JsonNode videos, VideoQuality quality,
                                         boolean strictQuality, int heightLimit) {
        JsonNode highest = null;
        JsonNode lowest = null;
        JsonNode exact = null;
        JsonNode lower = null;
        int target = quality == VideoQuality.AUTO
                ? heightLimit : quality.targetHeight();
        for (JsonNode video : videos) {
            int height = intValue(video.get("height"));
            if (height <= 0) continue;
            if (lowest == null || height < intValue(lowest.get("height"))) lowest = video;
            if (highest == null || height > intValue(highest.get("height"))) highest = video;
            if (quality.explicitHeight() && height == target
                    && (exact == null || longValue(video.get("bandwidth"))
                    > longValue(exact.get("bandwidth")))) {
                exact = video;
            }
            if (height <= target && (lower == null
                    || height > intValue(lower.get("height"))
                    || (height == intValue(lower.get("height"))
                    && longValue(video.get("bandwidth")) > longValue(lower.get("bandwidth"))))) {
                lower = video;
            }
        }
        if (highest == null) {
            throw new BilibiliApiException("BILI_PLAYURL_EMPTY", "B站没有返回有效视频流");
        }
        if (quality == VideoQuality.BEST) return highest;
        if (quality.explicitHeight() && strictQuality) {
            if (exact == null) {
                throw new BilibiliApiException("QUALITY_UNAVAILABLE",
                        "B站没有返回请求的清晰度");
            }
            return exact;
        }
        return lower == null ? lowest : lower;
    }

    public BilibiliModels.SubtitleTrack subtitles(String bvid, long cid) {
        if (!credential.authenticated()) return BilibiliModels.SubtitleTrack.empty();
        if (bvid == null || bvid.isBlank() || cid <= 0L) {
            return BilibiliModels.SubtitleTrack.empty();
        }
        Map<String, String> signed = signedQuery(Map.of(
                "bvid", bvid.trim(), "cid", Long.toString(cid)));
        JsonNode root = getJson(withQuery(playerWbiUri, signed), true,
                "BILI_SUBTITLE_FAILED");
        JsonNode tracks = root.path("data").path("subtitle").path("subtitles");
        if (!tracks.isArray() || tracks.isEmpty()) {
            return BilibiliModels.SubtitleTrack.empty();
        }
        JsonNode selected = null;
        for (JsonNode track : tracks) {
            String lan = text(track.get("lan"));
            if ("zh-Hans".equals(lan) || "ai-zh".equals(lan)
                    || "zh-CN".equals(lan) || "zh".equals(lan)) {
                selected = track;
                break;
            }
        }
        if (selected == null) selected = tracks.get(0);
        String url = text(selected.get("subtitle_url"));
        if (url.startsWith("//")) url = "https:" + url;
        URI subtitleUri;
        try {
            subtitleUri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return BilibiliModels.SubtitleTrack.empty();
        }
        String host = subtitleUri.getHost();
        if (host == null || !(host.endsWith(".hdslb.com")
                || host.endsWith(".bilibili.com"))) {
            return BilibiliModels.SubtitleTrack.empty();
        }
        JsonNode payload = getJson(subtitleUri, false, "BILI_SUBTITLE_DOWNLOAD_FAILED");
        JsonNode body = payload.path("body");
        List<BilibiliModels.SubtitleCue> cues = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode cue : body) {
                String content = text(cue.get("content"));
                if (content.isBlank()) continue;
                long from = Math.max(0L, Math.round(cue.path("from").asDouble(0.0) * 1000.0));
                long to = Math.max(from,
                        Math.round(cue.path("to").asDouble(0.0) * 1000.0));
                cues.add(new BilibiliModels.SubtitleCue(from, to, content));
            }
        }
        return new BilibiliModels.SubtitleTrack(text(selected.get("lan")), cues);
    }

    public List<BilibiliModels.CommentNotification> commentNotifications(int size) {
        Map<String, String> query = Map.of(
                "pn", "1", "ps", Integer.toString(Math.max(1, Math.min(size, 50))));
        JsonNode root = getJson(withQuery(msgfeedReplyUri, query), true,
                "BILI_COMMENT_NOTIFICATIONS_FAILED");
        JsonNode items = root.path("data").path("items");
        List<BilibiliModels.CommentNotification> values = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode notification : items) {
                JsonNode item = notification.path("item");
                JsonNode user = notification.path("user");
                String source = firstText(item.get("source_id"),
                        item.get("source_id_str"));
                String message = firstText(item.get("source_content"),
                        item.get("target_reply_content"), item.get("title"));
                if (source.isBlank() || message.isBlank()) continue;
                values.add(new BilibiliModels.CommentNotification(
                        firstText(notification.get("id")),
                        longValue(item.get("subject_id")),
                        intValue(item.get("business_id")),
                        longValue(user.get("mid")),
                        text(user.get("nickname")),
                        firstText(item.get("root_id"), item.get("root_id_str")),
                        source,
                        firstText(item.get("target_id"), item.get("target_id_str")),
                        message,
                        text(item.get("title")),
                        longValue(notification.get("reply_time"))));
            }
        }
        return List.copyOf(values);
    }

    /** 发布视频顶层评论或评论树回复；root/parent 为空时发顶层评论。 */
    public BilibiliModels.SendResult replyToComment(String aid, String rootRpid,
                                                    String parentRpid, String message) {
        if (aid == null || aid.isBlank()) throw new IllegalArgumentException("aid 不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("评论正文不能为空");
        }
        if (message.length() > 1000) {
            throw new IllegalArgumentException("评论正文最多 1000 字符");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("type", "1");
        form.put("oid", aid.trim());
        form.put("message", message);
        form.put("plat", "1");
        form.put("csrf", credential.biliJct());
        if (rootRpid != null && !rootRpid.isBlank()) {
            form.put("root", rootRpid.trim());
            form.put("parent", parentRpid == null || parentRpid.isBlank()
                    ? rootRpid.trim() : parentRpid.trim());
        } else if (parentRpid != null && !parentRpid.isBlank()) {
            throw new IllegalArgumentException("回复评论必须提供 rootRpid");
        }
        JsonNode root = postForm(replyAddUri, form, true, "BILI_COMMENT_SEND_FAILED");
        JsonNode data = root.path("data");
        String rpid = firstText(data.get("rpid_str"), data.get("rpid"));
        return new BilibiliModels.SendResult(true, rpid, "", "OK");
    }

    public BilibiliModels.SessionPage newSessions(long beginTsMicros) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("begin_ts", Long.toString(Math.max(0L, beginTsMicros)));
        query.put("size", "20");
        query.put("build", "0");
        query.put("mobi_app", "web");
        JsonNode root = getJson(withQuery(newSessionsUri, query), true,
                "BILI_SESSION_POLL_FAILED");
        JsonNode data = root.path("data");
        JsonNode list = data.path("session_list");
        List<BilibiliModels.PrivateSession> sessions = new ArrayList<>();
        long next = beginTsMicros;
        if (list.isArray()) {
            for (JsonNode node : list) {
                long talkerId = longValue(node.get("talker_id"));
                if (talkerId <= 0L) continue;
                long sessionTs = longValue(node.get("session_ts"));
                if (sessionTs > next) next = sessionTs;
                String name = text(node.path("account_info").get("name"));
                sessions.add(new BilibiliModels.PrivateSession(
                        talkerId,
                        intValue(node.get("session_type")),
                        longValue(node.get("unread_count")),
                        longValue(node.get("ack_seqno")),
                        longValue(node.get("max_seqno")),
                        sessionTs,
                        name));
            }
        }
        long ackTs = longValue(data.get("ack_ts"));
        if (ackTs > next) next = ackTs;
        return new BilibiliModels.SessionPage(List.copyOf(sessions), next);
    }

    public BilibiliModels.MessagePage fetchSessionMessages(
            long talkerId, int sessionType, long beginSeqno, int size) {
        if (talkerId <= 0L) throw new IllegalArgumentException("talkerId 必须为正");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("talker_id", Long.toString(talkerId));
        query.put("session_type", Integer.toString(sessionType));
        query.put("begin_seqno", Long.toString(Math.max(0L, beginSeqno)));
        query.put("size", Integer.toString(Math.max(1, Math.min(size, 100))));
        query.put("build", "0");
        query.put("mobi_app", "web");
        JsonNode root = getJson(withQuery(fetchSessionMessagesUri, query), true,
                "BILI_DM_FETCH_FAILED");
        JsonNode messagesNode = root.path("data").path("messages");
        List<BilibiliModels.PrivateMessage> messages = new ArrayList<>();
        long min = 0L;
        long max = 0L;
        if (messagesNode.isArray()) {
            for (JsonNode node : messagesNode) {
                long seq = longValue(node.get("msg_seqno"));
                if (seq > 0L) {
                    if (min == 0L || seq < min) min = seq;
                    if (seq > max) max = seq;
                }
                messages.add(new BilibiliModels.PrivateMessage(
                        longValue(node.get("sender_uid")),
                        longValue(node.get("receiver_id")),
                        intValue(node.get("receiver_type")),
                        intValue(node.get("msg_type")),
                        text(node.get("content")),
                        seq,
                        longValue(node.get("timestamp")),
                        firstText(node.get("msg_key_str"), node.get("msg_key")),
                        intValue(node.get("msg_status"))));
            }
        }
        messages.sort(Comparator.comparingLong(BilibiliModels.PrivateMessage::msgSeqno));
        return new BilibiliModels.MessagePage(messages, min, max);
    }

    public void updateAck(long talkerId, int sessionType, long ackSeqno) {
        if (talkerId <= 0L || ackSeqno <= 0L) return;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("talker_id", Long.toString(talkerId));
        form.put("session_type", Integer.toString(sessionType));
        form.put("ack_seqno", Long.toString(ackSeqno));
        form.put("csrf_token", credential.biliJct());
        form.put("csrf", credential.biliJct());
        form.put("build", "0");
        form.put("mobi_app", "web");
        postForm(updateAckUri, form, true, "BILI_DM_ACK_FAILED");
    }

    public BilibiliModels.SendResult sendPrivateMessage(long receiverId, String text) {
        if (receiverId <= 0L) throw new IllegalArgumentException("receiverId 必须为正");
        BilibiliModels.AccountInfo self = validateAccount();
        String contentJson;
        try {
            contentJson = json.writeValueAsString(Map.of("content", text));
        } catch (IOException impossible) {
            throw new IllegalStateException("B站私信内容序列化失败", impossible);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("msg[sender_uid]", Long.toString(self.mid()));
        form.put("msg[receiver_id]", Long.toString(receiverId));
        form.put("msg[receiver_type]", "1");
        form.put("msg[msg_type]", "1");
        form.put("msg[msg_status]", "0");
        form.put("msg[content]", contentJson);
        form.put("msg[timestamp]", Long.toString(Instant.now().getEpochSecond()));
        form.put("msg[new_face_version]", "0");
        form.put("msg[dev_id]", deviceId);
        form.put("from_filework", "0");
        form.put("build", "0");
        form.put("mobi_app", "web");
        form.put("csrf_token", credential.biliJct());
        form.put("csrf", credential.biliJct());
        Map<String, String> signed = signedQuery(Map.of(
                "w_sender_uid", Long.toString(self.mid()),
                "w_receiver_id", Long.toString(receiverId),
                "w_dev_id", deviceId));
        URI uri = withQuery(sendMessageUri, signed);
        JsonNode root = postForm(uri, form, true, "BILI_DM_SEND_FAILED");
        JsonNode data = root.path("data");
        String msgKey = firstText(data.get("msg_key_str"), data.get("msg_key"));
        return new BilibiliModels.SendResult(true, msgKey, "", "OK");
    }

    public long selfUid() {
        return validateAccount().mid();
    }

    @Override
    public void close() {
        http.close();
        mediaHttp.close();
    }

    private JsonNode getJson(URI uri, boolean auth, String errorCode) {
        HttpRequest request = requestBuilder(uri, auth)
                .GET()
                .build();
        return send(request, errorCode);
    }

    private JsonNode postForm(URI uri, Map<String, String> form, boolean auth,
                              String errorCode) {
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = requestBuilder(uri, auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, errorCode);
    }

    private HttpRequest.Builder requestBuilder(URI uri, boolean auth) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*");
        if (auth && credential.authenticated()) {
            builder.header("Cookie", credential.cookieHeader());
        }
        return builder;
    }

    private JsonNode send(HttpRequest request, String errorCode) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BilibiliTransportException("BILI_INTERRUPTED",
                    "B站请求被中断");
        } catch (IOException failure) {
            throw new BilibiliTransportException("BILI_IO",
                    "B站网络请求失败");
        }
        if (response.statusCode() == 412) {
            throw new BilibiliApiException("BILI_RISK_CONTROL",
                    "B站风控拦截（HTTP 412）");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BilibiliApiException(errorCode,
                    "B站 HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (IOException invalid) {
            throw new BilibiliTransportException("BILI_INVALID_JSON",
                    "B站响应不是有效 JSON");
        }
        int code = intValue(root.get("code"));
        if (code != 0) {
            String message = firstNonBlank(text(root.get("message")),
                    text(root.get("msg")), "B站接口拒绝");
            throw new BilibiliApiException(codeName(errorCode, code), message);
        }
        return root;
    }

    private Map<String, String> signedQuery(Map<String, String> input) {
        ensureWbiKeys();
        String mixinKey = mixinKey(imgKey + subKey);
        Map<String, String> signed = new LinkedHashMap<>(input);
        signed.put("wts", Long.toString(Instant.now().getEpochSecond()));
        signed = signed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> filterValue(entry.getValue()),
                        (left, right) -> left, LinkedHashMap::new));
        String query = signed.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        signed.put("w_rid", md5Hex(query + mixinKey));
        return signed;
    }

    private synchronized void ensureWbiKeys() {
        Instant now = Instant.now();
        if (!imgKey.isBlank() && !subKey.isBlank() && now.isBefore(wbiExpiresAt)) {
            return;
        }
        JsonNode root = getJson(navUri, true, "BILI_WBI_FAILED");
        JsonNode wbi = root.path("data").path("wbi_img");
        String imgUrl = text(wbi.get("img_url"));
        String subUrl = text(wbi.get("sub_url"));
        imgKey = keyFromUrl(imgUrl);
        subKey = keyFromUrl(subUrl);
        if (imgKey.isBlank() || subKey.isBlank()) {
            throw new BilibiliApiException("BILI_WBI_KEYS_MISSING",
                    "B站 WBI key 缺失");
        }
        wbiExpiresAt = now.plus(WBI_TTL);
    }

    private static String mixinKey(String joined) {
        StringBuilder result = new StringBuilder(32);
        for (int index : MIXIN_KEY_ENC_TAB) {
            if (index >= 0 && index < joined.length() && result.length() < 32) {
                result.append(joined.charAt(index));
            }
        }
        return result.toString();
    }

    private static String keyFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String last = url.substring(url.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot < 0 ? last : last.substring(0, dot);
    }

    private static String filterValue(String value) {
        return String.valueOf(value).replaceAll("[!'()*]", "");
    }

    private static URI withQuery(URI base, Map<String, String> query) {
        String suffix = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(base + (suffix.isEmpty() ? "" : "?" + suffix));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String md5Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 不可用", impossible);
        }
    }

    private static String codeName(String prefix, int code) {
        return prefix + "_" + code;
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull()) {
                String value = node.isTextual() ? node.asText() : node.asText("");
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return "";
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static long longValue(JsonNode node) {
        if (node == null || node.isNull()) return 0L;
        if (node.isNumber()) return node.asLong();
        try {
            return Long.parseLong(node.asText("0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int intValue(JsonNode node) {
        long value = longValue(node);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String requireBaseUrl(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))
                || normalized.endsWith("/")) {
            throw new IllegalArgumentException(name + " 必须是 http(s) 且不能以 / 结尾");
        }
        return normalized;
    }

    /** B 站接口明确返回非零 code。 */
    public static final class BilibiliApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public BilibiliApiException(String code, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    /** 网络中断、超时或响应不可解析；调用方必须按 cannot-confirm 处理。 */
    public static final class BilibiliTransportException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public BilibiliTransportException(String code, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }
}
