package io.mosire.iibot.connect.bilibili;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Bilibili Adapter 的运行时配置；凭据文件路径由 app 层从 env 解析。 */
public record BilibiliAdapterConfig(
        String accountId,
        Path credentialFile,
        Duration pollInterval,
        boolean receivePrivateMessages,
        boolean receiveCommentReplies,
        boolean writeEnabled,
        int maxWritesPerDay) {

    public BilibiliAdapterConfig {
        accountId = requireText(accountId, "accountId");
        credentialFile = Objects.requireNonNull(credentialFile, "credentialFile")
                .toAbsolutePath().normalize();
        pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.compareTo(Duration.ofSeconds(10)) < 0
                || pollInterval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("pollInterval 必须在 10s..1h");
        }
        if (maxWritesPerDay < 0 || maxWritesPerDay > 100) {
            throw new IllegalArgumentException("maxWritesPerDay 必须在 0..100");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }
}
