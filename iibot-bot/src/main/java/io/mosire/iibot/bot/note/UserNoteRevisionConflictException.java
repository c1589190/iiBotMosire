package io.mosire.iibot.bot.note;

/** 用户备注乐观锁冲突。 */
public final class UserNoteRevisionConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long expectedRevision;
    private final long actualRevision;

    public UserNoteRevisionConflictException(long expectedRevision, long actualRevision) {
        super("用户备注 revision 冲突: expected=" + expectedRevision
                + " actual=" + actualRevision);
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long actualRevision() {
        return actualRevision;
    }
}
