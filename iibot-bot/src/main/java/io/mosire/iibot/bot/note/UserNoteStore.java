package io.mosire.iibot.bot.note;

import java.util.List;
import java.util.Optional;

/** 用户备注持久化端口。 */
public interface UserNoteStore {

    Optional<UserNote> find(String accountId, String qqId);

    /** 按 priority 降序、更新时间降序返回。 */
    List<UserNote> list(int limit);

    /**
     * 新建或替换备注。
     *
     * @param expectedRevision 新建时只接受 null 或 0；更新时必须等于当前 revision
     * @throws UserNoteRevisionConflictException 乐观锁冲突
     */
    UserNote upsert(UserNoteDraft draft, Long expectedRevision);
}
