package io.mosire.iibot.app.delivery;

/**
 * 宿主外发投递声明的持久化端口。
 *
 * <p>每个待外发结果必须有稳定且唯一的 key。首次 {@link #claim(String)} 成功返回 true；
 * 同一 key 已存在时返回 false。实现必须保证声明先持久化成功，再允许调用方发送，从而在
 * 进程崩溃/重启后避免重复发送。</p>
 *
 * <p>任何持久化失败都不得静默吞掉，必须抛出 {@link DeliveryLedgerException}。调用方在
 * 收到异常时不得继续执行对应外发，因为无法证明该声明已经安全落盘。</p>
 */
public interface DeliveryLedger {

    /**
     * 原子声明一个投递 key。
     *
     * @param key 稳定投递键，不能为空
     * @return 首次声明返回 true；key 已存在返回 false
     * @throws DeliveryLedgerException 声明无法可靠持久化或 key 非法
     */
    boolean claim(String key);
}
