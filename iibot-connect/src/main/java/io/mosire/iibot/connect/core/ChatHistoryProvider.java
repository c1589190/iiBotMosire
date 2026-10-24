package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/**
 * 适配器可选的只读聊天历史能力。
 *
 * <p>该端口只返回协议中立的文本消息，不发送消息、不写入 app 会话 cache，也不把
 * OneBot 原始 JSON 暴露给上层。实现必须把鉴权、HTTP/WS、游标和错误映射封装在
 * adapter 内。</p>
 *
 * <p>历史接口是外部 QQ 客户端的有限缓存视图，不是领域事实源；历史可能被清理，
 * 也可能因 NapCat 的短 {@code message_seq} 游标淘汰而提前结束。</p>
 */
public interface ChatHistoryProvider {

    /**
     * 读取一页聊天历史。实现不得自动重试，也不得因读取失败产生发送副作用。
     *
     * @param query 查询条件；游标为空表示从最新消息方向开始
     * @return 文本历史页；时间、发送者或正文不完整的历史片段应被跳过
     */
    CompletionStage<ChatHistoryPage> history(ChatHistoryQuery query);
}
