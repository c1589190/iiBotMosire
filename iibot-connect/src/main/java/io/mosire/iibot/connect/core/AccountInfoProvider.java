package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/**
 * 可查询当前 Bot 账号公开信息的适配器能力。
 *
 * <p>实现必须把具体协议细节留在适配器内部，只返回领域中立的 {@link AccountInfo}。
 * 查询失败时实现应返回异常完成的 {@link CompletionStage}，而不是同步抛出底层异常；
 * 异常消息与异常链不得包含 access token、Authorization 头、完整连接 URL 或其他凭据。</p>
 *
 * <p>该查询是只读操作。实现不得因查询失败而自动重试，也不得产生外部副作用。</p>
 */
public interface AccountInfoProvider {

    /**
     * 查询当前 Bot 账号标识与昵称。
     *
     * @return 账号信息的 future；失败时以脱敏异常完成
     */
    CompletionStage<AccountInfo> accountInfo();
}
