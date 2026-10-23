package io.mosire.iibot.taskflow.event;

/**
 * 事件追加端口。
 *
 * <p>append 必须返回存储分配的单调递增 seq；同一领域命令只调用一次 append。
 * 生产实现可以是持久事件库，本模块先提供纯内存实现用于单测。</p>
 */
@FunctionalInterface
public interface EventSink {
    long append(TaskflowEvent event);
}
