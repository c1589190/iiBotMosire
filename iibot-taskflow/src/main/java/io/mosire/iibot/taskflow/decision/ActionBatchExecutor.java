package io.mosire.iibot.taskflow.decision;

/**
 * 宿主推演批次执行端口。当前实现只需记录批次或返回一个稳定结果；不得连接真实 LLM。
 *
 * <p>返回的 {@link ActionBatch} 必须以 {@link ActionBatchStatus#COMMITTED} 表示执行成功。
 * 返回其他终态或异常时，裁定包提交端口按失败回执处理，不会把行动标成 COMMITTED。</p>
 */
@FunctionalInterface
public interface ActionBatchExecutor {

    ActionBatch execute(ActionBatch batch);
}
