package io.mosire.iibot.taskflow.action;

/**
 * 行动池持久化端口。实现方必须在 {@link #save(ActionPoolSnapshot)} 返回成功后才算提交成功；
 * 抛出异常表示本次变更未落定。
 */
public interface ActionPoolStore {

    String RESOURCE_NAMESPACE = "action-pool";

    ActionPoolSnapshot load();

    void save(ActionPoolSnapshot snapshot);
}
