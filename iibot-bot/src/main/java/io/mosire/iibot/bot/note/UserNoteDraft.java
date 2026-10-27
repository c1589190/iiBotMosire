package io.mosire.iibot.bot.note;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 新建或替换一条用户备注的输入。
 *
 * <p>标签没有宿主内置含义；{@code priority} 只用于列表/Inbox 排序。</p>
 */
public record UserNoteDraft(
        String accountId,
        String qqId,
        String displayName,
        Set<String> tags,
        String note,
        int priority) {

    public UserNoteDraft {
        accountId = requireText(accountId, "accountId");
        qqId = requireText(qqId, "qqId");
        displayName = displayName == null ? "" : displayName.trim();
        tags = normalizeTags(tags);
        note = note == null ? "" : note.trim();
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
    }

    private static Set<String> normalizeTags(Set<String> tags) {
        if (tags == null) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = requireText(tag, "tag");
            if (value.length() > 64) {
                throw new IllegalArgumentException("tag 不得超过 64 字符");
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("tag 不得包含换行");
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
