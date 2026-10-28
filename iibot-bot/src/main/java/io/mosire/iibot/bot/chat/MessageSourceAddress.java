package io.mosire.iibot.bot.chat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 统一消息来源地址。
 *
 * <p>规范形式为 {@code family_kind/segment...}。当前定义：</p>
 *
 * <pre>
 * qq_group/{accountId}/{groupId}
 * qq_private/{accountId}/{peerId}
 * qq_system/{accountId}/{eventType}
 * app_system/{eventType}
 * </pre>
 *
 * <p>地址段使用百分号编码，{@code /} 只作为分隔符；非法前缀、空段、错误段数
 * 或畸形百分号编码均 fail-closed。QQ 之外的消息族（wechat、discord 等）只要沿用
 * {@code family_kind} 形式即可解析，不需要改本类。</p>
 */
public record MessageSourceAddress(String family, String kind, List<String> segments) {

    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9-]*");
    private static final int MAX_RAW_LENGTH = 1024;
    private static final int MAX_SEGMENTS = 16;
    private static final int MAX_SEGMENT_LENGTH = 256;

    public MessageSourceAddress {
        family = requireToken(family, "family");
        kind = requireToken(kind, "kind");
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("来源地址至少需要一个 segment");
        }
        if (segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("来源地址 segment 过多");
        }
        List<String> normalized = new ArrayList<>(segments.size());
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("来源地址 segment 不能为空");
            }
            if (segment.length() > MAX_SEGMENT_LENGTH) {
                throw new IllegalArgumentException("来源地址 segment 过长");
            }
            normalized.add(segment);
        }
        segments = List.copyOf(normalized);
        validateShape(family, kind, segments);
    }

    /** QQ 群会话来源：{@code qq_group/account/group}。 */
    public static MessageSourceAddress qqGroup(String accountId, String groupId) {
        return new MessageSourceAddress("qq", "group", List.of(accountId, groupId));
    }

    /** QQ 私聊来源：{@code qq_private/account/peer}。 */
    public static MessageSourceAddress qqPrivate(String accountId, String peerId) {
        return new MessageSourceAddress("qq", "private", List.of(accountId, peerId));
    }

    /** QQ 协议/账号系统事件：{@code qq_system/account/event}。 */
    public static MessageSourceAddress qqSystem(String accountId, String eventType) {
        return new MessageSourceAddress("qq", "system", List.of(accountId, eventType));
    }

    /** 应用系统事件：{@code app_system/event}；允许 event 后附带子路径。 */
    public static MessageSourceAddress appSystem(String... eventPath) {
        if (eventPath == null || eventPath.length == 0) {
            throw new IllegalArgumentException("app_system 至少需要一个事件段");
        }
        return new MessageSourceAddress("app", "system", List.of(eventPath));
    }

    /** 解析 canonical source；只接受合法 family/kind/segment 组合。 */
    public static MessageSourceAddress parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_RAW_LENGTH) {
            throw new IllegalArgumentException("来源地址为空或过长");
        }
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("来源地址不能有空段");
        }
        String[] parts = value.split("/", -1);
        String prefix = parts[0];
        int separator = prefix.indexOf('_');
        if (separator <= 0 || separator == prefix.length() - 1) {
            throw new IllegalArgumentException("来源地址前缀必须是 family_kind");
        }
        String family = prefix.substring(0, separator);
        String kind = prefix.substring(separator + 1);
        List<String> segments = new ArrayList<>(parts.length - 1);
        for (int index = 1; index < parts.length; index++) {
            String segment = decode(parts[index]);
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("来源地址 segment 不能为空");
            }
            segments.add(segment);
        }
        return new MessageSourceAddress(family, kind, segments);
    }

    /** 从旧版 {@code thread:<group|private>:<base64>:<base64>} 键解码。 */
    public static MessageSourceAddress fromLegacyThreadId(String threadId) {
        ChatSourceKey key = ChatSourceKey.fromThreadId(threadId);
        return key.sourceAddress();
    }

    /** 规范地址文本。 */
    public String canonical() {
        StringBuilder result = new StringBuilder(family).append('_').append(kind);
        for (String segment : segments) {
            result.append('/').append(encode(segment));
        }
        return result.toString();
    }

    /** 是否为 QQ 群来源。 */
    public boolean isQqGroup() {
        return "qq".equals(family) && "group".equals(kind);
    }

    /** 是否为 QQ 私聊来源。 */
    public boolean isQqPrivate() {
        return "qq".equals(family) && "private".equals(kind);
    }

    /** 是否为系统来源。 */
    public boolean isSystem() {
        return "system".equals(kind);
    }

    /** 群/私聊来源所属的 Bot 账号；应用系统来源为空串。 */
    public String accountId() {
        return isSystem() && "app".equals(family) ? "" : segments.getFirst();
    }

    /** 群号或私聊对方 QQ；仅群/私聊来源有值。 */
    public String targetId() {
        return "group".equals(kind) || "private".equals(kind) ? segments.get(1) : "";
    }

    /** 系统事件路径；非 system 来源返回空列表。 */
    public List<String> eventPath() {
        if (!isSystem()) {
            return List.of();
        }
        return "app".equals(family) ? segments : segments.subList(1, segments.size());
    }

    /** 只有 QQ 群/私聊来源能映射为消息板 thread。 */
    public ChatSourceKey toChatSourceKey() {
        if (isQqGroup()) {
            return ChatSourceKey.groupChat(segments.get(0), segments.get(1));
        }
        if (isQqPrivate()) {
            return ChatSourceKey.privateChat(segments.get(0), segments.get(1));
        }
        throw new IllegalArgumentException("系统来源不能映射为消息板 thread: " + canonical());
    }

    @Override
    public String toString() {
        return canonical();
    }

    private static void validateShape(String family, String kind, List<String> segments) {
        if ("group".equals(kind) || "private".equals(kind)) {
            if (segments.size() != 2) {
                throw new IllegalArgumentException(kind + " 来源必须包含 account 和 target");
            }
            return;
        }
        if ("system".equals(kind)) {
            if ("app".equals(family) && segments.isEmpty()) {
                throw new IllegalArgumentException("app_system 至少需要一个事件段");
            }
            if ("qq".equals(family) && segments.size() < 2) {
                throw new IllegalArgumentException("qq_system 必须包含 account 和 event");
            }
        }
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " 非法: " + value);
        }
        return normalized;
    }

    private static String encode(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '.' || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return encoded.toString();
    }

    private static String decode(String value) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current != '%') {
                int codePoint = value.codePointAt(index);
                byte[] bytes = new String(Character.toChars(codePoint))
                        .getBytes(StandardCharsets.UTF_8);
                decoded.writeBytes(bytes);
                index += Character.charCount(codePoint);
                continue;
            }
            if (index + 2 >= value.length()) {
                throw new IllegalArgumentException("来源地址百分号编码不完整");
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("来源地址百分号编码非法");
            }
            decoded.write((high << 4) | low);
            index += 3;
        }
        String result = decoded.toString(StandardCharsets.UTF_8);
        for (int index = 0; index < result.length(); index++) {
            if (Character.isISOControl(result.charAt(index))) {
                throw new IllegalArgumentException("来源地址不能包含控制字符");
            }
        }
        return result;
    }
}
