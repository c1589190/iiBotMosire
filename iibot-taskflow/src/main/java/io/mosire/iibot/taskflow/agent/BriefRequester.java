package io.mosire.iibot.taskflow.agent;

/**
 * request_brief 工具的宿主回调。秘书会话负责 engine.requestBrief + issueBrief 与 revision
 * 推进；工具自身不直接访问未过滤世界。
 */
@FunctionalInterface
public interface BriefRequester {
    BriefReply requestBrief(String question);
}
