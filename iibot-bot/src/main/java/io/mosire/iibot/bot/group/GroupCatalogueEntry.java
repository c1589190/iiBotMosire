package io.mosire.iibot.bot.group;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 群目录条目：bot 见过的群（已在群里、收到邀请、收到入群申请）的稳定快照。
 *
 * <p>QQ 协议没有「搜群」接口，能检索的只有 bot 自己的关系面，因此目录就是搜索的事实来源：
 * 见过的群都会被记下来，之后可以按群号或群名找回。</p>
 *
 * @param groupId    群号
 * @param groupName  群名，可能为空
 * @param source     最近一次来源，见 {@code SOURCE_*}
 * @param lastSeenAt 最近一次被观察到的时刻
 */
public record GroupCatalogueEntry(
        String groupId,
        String groupName,
        String source,
        Instant lastSeenAt) {

    /** 来源：bot 已加入该群。 */
    public static final String SOURCE_JOINED = "joined";
    /** 来源：bot 收到该群的邀请。 */
    public static final String SOURCE_INVITE = "invite";
    /** 来源：该群出现了入群申请。 */
    public static final String SOURCE_JOIN_REQUEST = "join_request";

    public GroupCatalogueEntry {
        Objects.requireNonNull(groupId, "groupId");
        groupId = groupId.trim();
        if (groupId.isEmpty()) {
            throw new IllegalArgumentException("groupId 不能为空");
        }
        groupName = groupName == null ? "" : groupName.trim();
        source = source == null || source.isBlank() ? SOURCE_JOINED : source.trim();
        lastSeenAt = lastSeenAt == null ? Instant.now() : lastSeenAt;
    }

    /** 关键词是否命中群号或群名（大小写不敏感、空关键词命中一切）。 */
    public boolean matches(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return groupId.toLowerCase(Locale.ROOT).contains(needle)
                || groupName.toLowerCase(Locale.ROOT).contains(needle);
    }
}
