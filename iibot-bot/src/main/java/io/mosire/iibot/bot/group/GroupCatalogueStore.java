package io.mosire.iibot.bot.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 群目录存储：记录 bot 见过的群，供按群号/群名检索。 */
public interface GroupCatalogueStore {

    /** 写入或更新一条条目；同群号以本次观察为准。 */
    void upsert(GroupCatalogueEntry entry);

    /** 批量写入或更新。 */
    void upsertAll(Collection<GroupCatalogueEntry> entries);

    /** 按关键词检索（群号或群名包含），空关键词等于列出全部。 */
    List<GroupCatalogueEntry> search(String keyword, int limit);

    /** 列出最近的条目。 */
    List<GroupCatalogueEntry> list(int limit);

    /** 按群号取单条。 */
    Optional<GroupCatalogueEntry> get(String groupId);
}
