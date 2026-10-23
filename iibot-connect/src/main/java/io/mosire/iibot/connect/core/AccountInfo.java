package io.mosire.iibot.connect.core;

import java.util.Objects;

/**
 * 与具体聊天协议无关的 Bot 账号公开信息。
 *
 * <p>该类型只包含上层安全展示所需的账号标识与昵称，不包含 OneBot/NapCat 的原始响应、
 * 连接参数或任何凭据。实例由 record 保证不可变。</p>
 *
 * @param accountId Bot 的 QQ/账号标识
 * @param nickname  Bot 昵称；可为空串，但不能为 {@code null}
 */
public record AccountInfo(String accountId, String nickname) {

    /**
     * 规范化并校验账号信息。
     *
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException {@code accountId} 为空白
     */
    public AccountInfo {
        accountId = Objects.requireNonNull(accountId, "accountId");
        nickname = Objects.requireNonNull(nickname, "nickname");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空白");
        }
    }

    /**
     * 昵称的语义别名，便于上层使用 displayName 命名。
     *
     * @return 昵称
     */
    public String displayName() {
        return nickname;
    }
}
