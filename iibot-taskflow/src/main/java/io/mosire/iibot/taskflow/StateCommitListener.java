package io.mosire.iibot.taskflow;

/** 在成功写命令完成且引擎仍持锁时接收通知的宿主回调。 */
@FunctionalInterface
public interface StateCommitListener {
    /**
     * 成功写命令完成后的提交回调。
     *
     * <p>回调在引擎写锁内执行，可以安全读取引擎状态；异常必须向上传播，不能被吞掉。</p>
     */
    void afterCommit(TaskflowEngine engine, CommandResult result);
}
