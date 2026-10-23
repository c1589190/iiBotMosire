package io.mosire.iibot.connect.napcat;

import java.util.Objects;

/**
 * OneBot {@code get_login_info} 的公开结果。
 *
 * @param userId Bot 账号标识
 * @param nickname Bot 昵称
 */
public record LoginInfo(String userId, String nickname) {
    public LoginInfo {
        userId = Objects.requireNonNull(userId, "userId");
        nickname = Objects.requireNonNull(nickname, "nickname");
    }
}
