package io.mosire.iibot.connect.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** OneBot 客户端最近消息读取能力；独立于 Bot Inbox。 */
public interface RecentQqProvider {

    CompletionStage<List<RecentQqMessage>> recentMessages(int limit);
}
