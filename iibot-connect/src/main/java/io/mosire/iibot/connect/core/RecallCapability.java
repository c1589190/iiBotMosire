package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/** 可选消息撤回能力；实现负责按账号调用 provider 的受控撤回动作。 */
public interface RecallCapability {

    /**
     * 撤回一条消息。成功返回 SENT/消息 ID；失败以 completed future 的稳定异常或错误回执表达。
     */
    CompletionStage<SendReceipt> recall(String messageId);
}
