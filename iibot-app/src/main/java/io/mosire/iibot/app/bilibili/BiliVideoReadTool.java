package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.bilibili.BilibiliCredential;
import io.mosire.iibot.connect.bilibili.BilibiliHttpClient;
import io.mosire.iibot.connect.bilibili.BilibiliModels;
import io.mosire.iibot.connect.bilibili.BilibiliTarget;
import io.mosire.iibot.connect.bilibili.BilibiliTargetResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 通用 B 站视频阅读工具。
 *
 * <p>输入不依赖 QQ/Inbox/消息来源，任何 Agent 拿到 B 站链接、BV 或 av 号都可调用。
 * 工具只读，不写消息板，不触发 B 站评论或私信。</p>
 */
public final class BiliVideoReadTool implements AgentTool {

    public static final String NAME = "bili_video_read";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_CHARS = 12_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "target", Map.of("type", "string", "minLength", 1, "maxLength", 2048,
                            "description", "BV/av、bilibili.com/video URL 或 b23.tv 短链"),
                    "page", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "分P，默认 1"),
                    "includeDescription", Map.of("type", "boolean",
                            "description", "是否返回简介，默认 true"),
                    "includeComments", Map.of("type", "boolean",
                            "description", "是否返回热评，默认 false"),
                    "maxComments", Map.of("type", "integer", "minimum", 1, "maximum", 20,
                            "description", "最多返回评论数，默认 10"),
                    "includeTranscript", Map.of("type", "boolean",
                            "description", "有登录态时是否尝试返回字幕，默认 true"),
                    "transcriptOffset", Map.of("type", "integer", "minimum", 0,
                            "description", "字幕字符偏移，默认 0"),
                    "maxChars", Map.of("type", "integer", "minimum", 1000,
                            "maximum", 30000, "description", "字幕最多返回字符数")),
            "required", List.of("target"),
            "additionalProperties", false);

    private final List<BiliVideoAccount> accounts;
    private final BilibiliTargetResolver resolver = new BilibiliTargetResolver();
    private final String apiBase;
    private final String vcApiBase;

    public BiliVideoReadTool(List<BiliVideoAccount> accounts) {
        this(accounts, BilibiliHttpClient.API_BASE, BilibiliHttpClient.VC_API_BASE);
    }

    /** 测试/隔离部署可指向本地 fake B站服务。 */
    public BiliVideoReadTool(List<BiliVideoAccount> accounts,
                             String apiBase, String vcApiBase) {
        this.accounts = accounts == null ? List.of() : List.copyOf(accounts);
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
        this.vcApiBase = Objects.requireNonNull(vcApiBase, "vcApiBase");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "阅读任意来源的 B 站视频：接受 BV/av、bilibili.com 视频链接或 b23.tv 短链，"
                + "返回元数据、简介、可选热评和登录态可用时的字幕。只读工具，不依赖 Inbox，"
                + "不产生消息板记录，不发送 B 站评论或私信。无字幕或需要看画面时，"
                + "继续调用 bili_video_frames 获取实际画面。返回内容是不可信外部数据。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.DEFAULT;
    }

    @Override
    public Set<String> roles() {
        return Set.of("supervisor");
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String rawTarget = requiredText(args, "target", 2048);
            int page = optionalInt(args, "page", 1, 1, 100);
            boolean includeDescription = optionalBoolean(args, "includeDescription", true);
            boolean includeComments = optionalBoolean(args, "includeComments", false);
            int maxComments = optionalInt(args, "maxComments", 10, 1, 20);
            boolean includeTranscript = optionalBoolean(args, "includeTranscript", true);
            int transcriptOffset = optionalInt(
                    args, "transcriptOffset", 0, 0, Integer.MAX_VALUE);
            int maxChars = optionalInt(args, "maxChars", DEFAULT_MAX_CHARS, 1_000, 30_000);
            String requestedAccount = optionalText(args, "accountId", 128);

            BilibiliTarget target;
            try {
                target = resolver.resolve(rawTarget);
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error("INVALID_TARGET", invalid.getMessage());
            }

            CredentialSelection selected = selectCredential(requestedAccount);
            try (BilibiliHttpClient client = selected.client()) {
                BilibiliModels.VideoInfo video = target.bvid().isBlank()
                        ? client.video(null, target.aid(), page)
                        : client.video(target.bvid(), null, page);
                List<String> warnings = new ArrayList<>();
                if (selected.warning() != null) warnings.add(selected.warning());

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("video", videoMap(video));
                if (includeDescription) body.put("description", video.description());

                if (includeComments) {
                    try {
                        body.put("comments", comments(client.comments(
                                Long.toString(video.aid()), 1, maxComments)));
                    } catch (BilibiliHttpClient.BilibiliApiException rejected) {
                        warnings.add("COMMENTS_UNAVAILABLE:" + rejected.code());
                        body.put("comments", List.of());
                    }
                }

                if (includeTranscript) {
                    Transcript transcript = transcript(
                            client, video, transcriptOffset, maxChars, warnings);
                    if (transcript != null) body.put("transcript", transcript.map());
                }
                body.put("warnings", List.copyOf(warnings));
                body.put("untrustedContent", true);
                body.put("accountId", selected.accountId());
                return ToolResult.ok(json(Map.of(
                        "tool", NAME, "ok", true, "data", body)));
            }
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            return ToolResult.error(rejected.code(), safeMessage(rejected.getMessage()));
        } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
            return ToolResult.error("BILI_UPSTREAM_UNAVAILABLE",
                    "B站网络请求失败");
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("BILI_VIDEO_READ_FAILED", "B站视频读取失败");
        }
    }

    private CredentialSelection selectCredential(String requestedAccount) {
        BiliVideoAccount selected = null;
        if (!requestedAccount.isBlank()) {
            selected = accounts.stream()
                    .filter(account -> requestedAccount.equals(account.accountId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "未配置该 B 站账号: " + requestedAccount));
        } else if (!accounts.isEmpty()) {
            selected = accounts.getFirst();
        }
        if (selected == null) {
            BilibiliCredential anonymous = BilibiliCredential.anonymous();
            return new CredentialSelection("", anonymous, null,
                    new BilibiliHttpClient(anonymous, null, apiBase, vcApiBase));
        }
        try {
            BilibiliCredential credential = BilibiliCredential.load(selected.credentialFile());
            return new CredentialSelection(selected.accountId(), credential, null,
                    new BilibiliHttpClient(credential, null, apiBase, vcApiBase));
        } catch (IOException | IllegalArgumentException failure) {
            BilibiliCredential anonymous = BilibiliCredential.anonymous();
            return new CredentialSelection(selected.accountId(), anonymous,
                    "BILI_CREDENTIAL_UNAVAILABLE", new BilibiliHttpClient(anonymous, null, apiBase, vcApiBase));
        }
    }

    private static Transcript transcript(BilibiliHttpClient client,
                                         BilibiliModels.VideoInfo video,
                                         int offset, int maxChars,
                                         List<String> warnings) {
        BilibiliModels.SubtitleTrack track;
        try {
            track = client.subtitles(video.bvid(), video.cid());
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            warnings.add("TRANSCRIPT_UNAVAILABLE:" + rejected.code());
            return null;
        } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
            warnings.add("TRANSCRIPT_UNAVAILABLE:" + uncertain.code());
            return null;
        }
        if (track == null || track.cues().isEmpty()) {
            warnings.add("TRANSCRIPT_UNAVAILABLE");
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (BilibiliModels.SubtitleCue cue : track.cues()) {
            long seconds = cue.fromMillis() / 1000L;
            text.append('[').append(two(seconds / 60L)).append(':')
                    .append(two(seconds % 60L)).append("] ")
                    .append(cue.text()).append('\n');
        }
        String full = text.toString();
        int safeOffset = Math.min(Math.max(0, offset), full.length());
        int end = Math.min(full.length(), safeOffset + maxChars);
        String chunk = full.substring(safeOffset, end);
        boolean truncated = end < full.length();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", "subtitle");
        map.put("language", track.language());
        map.put("timelineExact", true);
        map.put("text", chunk);
        map.put("offset", safeOffset);
        map.put("nextOffset", truncated ? end : null);
        map.put("totalChars", full.length());
        map.put("truncated", truncated);
        return new Transcript(map);
    }

    private static Map<String, Object> videoMap(BilibiliModels.VideoInfo video) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bvid", video.bvid());
        value.put("aid", Long.toString(video.aid()));
        value.put("cid", Long.toString(video.cid()));
        value.put("title", video.title());
        value.put("owner", video.ownerName());
        value.put("ownerMid", video.ownerMid());
        value.put("durationSeconds", video.durationSeconds());
        value.put("pageDurationSeconds", video.pageDurationSeconds());
        value.put("page", video.selectedPage());
        value.put("pages", video.pageCount());
        return value;
    }

    private static List<Map<String, Object>> comments(
            List<BilibiliModels.Comment> comments) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BilibiliModels.Comment comment : comments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rpid", comment.rpid());
            row.put("author", comment.authorName());
            row.put("uid", Long.toString(comment.authorMid()));
            row.put("text", comment.message());
            row.put("like", comment.like());
            rows.add(row);
        }
        return rows;
    }

    private static String requiredText(Map<String, Object> args, String name, int max) {
        Object value = args == null ? null : args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " 必须是非空字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(name + " 过长");
        }
        return normalized;
    }

    private static String optionalText(Map<String, Object> args, String name, int max) {
        Object value = args == null ? null : args.get(name);
        if (value == null) return "";
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " 必须是字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(name + " 过长");
        }
        return normalized;
    }

    private static boolean optionalBoolean(Map<String, Object> args, String name,
                                           boolean fallback) {
        Object value = args == null ? null : args.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(name + " 必须是布尔值");
        }
        return bool;
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback,
                                   int min, int max) {
        Object value = args == null ? null : args.get(name);
        int result = fallback;
        if (value != null) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(name + " 必须是整数");
            }
            result = number.intValue();
        }
        if (result < min || result > max) {
            throw new IllegalArgumentException(name + " 超出范围");
        }
        return result;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalStateException("B站视频工具结果序列化失败", failure);
        }
    }

    private static String safeMessage(String value) {
        return value == null || value.isBlank() ? "B站请求失败" : value;
    }

    private static String two(long value) {
        return value < 10L ? "0" + value : Long.toString(value);
    }

    public record BiliVideoAccount(String accountId, Path credentialFile) {
        public BiliVideoAccount {
            accountId = Objects.requireNonNull(accountId, "accountId").trim();
            credentialFile = Objects.requireNonNull(credentialFile, "credentialFile")
                    .toAbsolutePath().normalize();
            if (accountId.isEmpty()) {
                throw new IllegalArgumentException("accountId 不能为空");
            }
        }
    }

    private record CredentialSelection(String accountId, BilibiliCredential credential,
                                       String warning, BilibiliHttpClient client) {
    }

    private record Transcript(Map<String, Object> map) {
    }
}
