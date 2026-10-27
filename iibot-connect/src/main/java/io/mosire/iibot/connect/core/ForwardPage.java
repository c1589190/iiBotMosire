package io.mosire.iibot.connect.core;

import java.util.List;

/** 合并转发读取结果；truncated 表示因上限未返回全部节点。 */
public record ForwardPage(List<ForwardMessage> messages, boolean truncated) {

    public ForwardPage {
        messages = List.copyOf(messages);
    }
}
