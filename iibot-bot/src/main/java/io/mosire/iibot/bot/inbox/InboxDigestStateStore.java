package io.mosire.iibot.bot.inbox;

import java.util.Optional;

/** thread → active Inbox digest item 的持久游标。 */
public interface InboxDigestStateStore {

    Optional<InboxDigestState> find(String threadId);

    void save(InboxDigestState state);

    void clear(String threadId);
}
