package io.mosire.iibot.connect.video.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 视频站账号的轻量引用。只保存账号标识与凭据文件位置，不读取或暴露凭据内容。
 */
public record VideoAccountRef(String accountId, Path credentialFile) {

    public VideoAccountRef {
        accountId = Objects.requireNonNull(accountId, "accountId").trim();
        credentialFile = Objects.requireNonNull(credentialFile, "credentialFile")
                .toAbsolutePath().normalize();
        if (accountId.isEmpty()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
    }
}
