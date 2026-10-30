package io.mosire.iibot.connect.video.bilibili;

import java.util.List;
import java.util.Objects;

/** Bilibili Adapter 使用的协议中立数据模型；不包含 Cookie、签名或原始响应。 */
public final class BilibiliModels {

    private BilibiliModels() {
    }

    public record AccountInfo(long mid, String name) {
        public AccountInfo {
            if (mid <= 0L) throw new IllegalArgumentException("B站 mid 必须为正");
            name = name == null ? "" : name.trim();
        }
    }

    public record VideoInfo(
            String bvid,
            long aid,
            long cid,
            String title,
            String description,
            String ownerName,
            String ownerMid,
            long durationSeconds,
            long pageDurationSeconds,
            int pageCount,
            int selectedPage) {
        public VideoInfo {
            bvid = requireText(bvid, "bvid");
            if (aid <= 0L || cid <= 0L) {
                throw new IllegalArgumentException("B站 aid/cid 必须为正");
            }
            title = title == null ? "" : title.trim();
            description = description == null ? "" : description.trim();
            ownerName = ownerName == null ? "" : ownerName.trim();
            ownerMid = ownerMid == null ? "" : ownerMid.trim();
            if (durationSeconds < 0L || pageDurationSeconds < 0L) {
                throw new IllegalArgumentException("时长不能为负");
            }
            if (pageCount < 1 || selectedPage < 1 || selectedPage > pageCount) {
                throw new IllegalArgumentException("分P参数非法");
            }
        }
    }

    public record Comment(
            String rpid,
            String rootRpid,
            String parentRpid,
            long authorMid,
            String authorName,
            String message,
            long like,
            long createdAtSeconds,
            int state) {
        public Comment {
            rpid = requireText(rpid, "rpid");
            rootRpid = rootRpid == null ? "" : rootRpid.trim();
            parentRpid = parentRpid == null ? "" : parentRpid.trim();
            authorName = authorName == null ? "" : authorName.trim();
            message = message == null ? "" : message;
        }
    }

    public record PlayStream(String url, String backupUrl, int width, int height,
                             String codec, long bandwidth, long expectedBytes) {
        public PlayStream {
            url = url == null ? "" : url.trim();
            backupUrl = backupUrl == null ? "" : backupUrl.trim();
            codec = codec == null ? "" : codec.trim();
            if (expectedBytes < 0L) expectedBytes = 0L;
            if (url.isEmpty() && backupUrl.isEmpty()) {
                throw new IllegalArgumentException("播放流缺少 URL");
            }
        }

        public String preferredUrl() {
            return url.isEmpty() ? backupUrl : url;
        }
    }

    public record SubtitleTrack(String language, List<SubtitleCue> cues) {
        public SubtitleTrack {
            language = language == null ? "" : language.trim();
            cues = cues == null ? List.of() : List.copyOf(cues);
        }

        public static SubtitleTrack empty() {
            return new SubtitleTrack("", List.of());
        }
    }

    public record SubtitleCue(long fromMillis, long toMillis, String text) {
        public SubtitleCue {
            if (fromMillis < 0L || toMillis < fromMillis) {
                throw new IllegalArgumentException("字幕时间轴非法");
            }
            text = text == null ? "" : text.trim();
        }
    }

    public record CommentNotification(
            String notificationId,
            long subjectId,
            int businessId,
            long userId,
            String userName,
            String rootRpid,
            String sourceRpid,
            String targetRpid,
            String message,
            String title,
            long replyTimeSeconds) {
        public CommentNotification {
            notificationId = notificationId == null ? "" : notificationId.trim();
            userName = userName == null ? "" : userName.trim();
            rootRpid = rootRpid == null ? "" : rootRpid.trim();
            sourceRpid = sourceRpid == null ? "" : sourceRpid.trim();
            targetRpid = targetRpid == null ? "" : targetRpid.trim();
            message = message == null ? "" : message;
            title = title == null ? "" : title.trim();
        }
    }

    public record SessionPage(List<PrivateSession> sessions, long nextBeginTsMicros) {
        public SessionPage {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
            if (nextBeginTsMicros < 0L) {
                throw new IllegalArgumentException("nextBeginTsMicros 不能为负");
            }
        }

        public static SessionPage empty(long beginTsMicros) {
            return new SessionPage(List.of(), beginTsMicros);
        }
    }

    public record PrivateSession(
            long talkerId,
            int sessionType,
            long unreadCount,
            long ackSeqno,
            long maxSeqno,
            long sessionTsMicros,
            String talkerName) {
        public PrivateSession {
            if (talkerId <= 0L) throw new IllegalArgumentException("talkerId 必须为正");
            if (sessionType <= 0) throw new IllegalArgumentException("sessionType 必须为正");
            talkerName = talkerName == null ? "" : talkerName.trim();
        }
    }

    public record PrivateMessage(
            long senderUid,
            long receiverId,
            int receiverType,
            int msgType,
            String content,
            long msgSeqno,
            long timestampSeconds,
            String msgKey,
            int msgStatus) {
        public PrivateMessage {
            content = content == null ? "" : content;
            msgKey = msgKey == null ? "" : msgKey.trim();
        }
    }

    public record MessagePage(List<PrivateMessage> messages, long minSeqno, long maxSeqno) {
        public MessagePage {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public static MessagePage empty() {
            return new MessagePage(List.of(), 0L, 0L);
        }
    }

    public record SendResult(boolean accepted, String msgKey, String code, String message) {
        public SendResult {
            msgKey = msgKey == null ? "" : msgKey.trim();
            code = code == null ? "" : code.trim();
            message = message == null ? "" : message.trim();
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }
}
