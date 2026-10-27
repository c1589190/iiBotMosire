package io.mosire.iibot.bot.weight;

/** 来源权重策略的乐观锁冲突。 */
public final class SourceWeightConflictException extends RuntimeException {

    private final long expectedRevision;
    private final long actualRevision;

    public SourceWeightConflictException(long expectedRevision, long actualRevision) {
        super("来源权重策略 revision 冲突");
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
