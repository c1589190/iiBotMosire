package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.connect.bilibili.BilibiliCredential;
import io.mosire.iibot.connect.bilibili.BilibiliLoginClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B 站扫码登录会话管理。二维码 key 只保存在进程内，不直接暴露给模型；模型只拿 loginId。
 */
public final class BiliLoginService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TTL = Duration.ofMinutes(5);

    private final List<BiliVideoReadTool.BiliVideoAccount> accounts;
    private final String passportBase;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public BiliLoginService(List<BiliVideoReadTool.BiliVideoAccount> accounts) {
        this(accounts, null);
    }

    public BiliLoginService(List<BiliVideoReadTool.BiliVideoAccount> accounts,
                            String passportBase) {
        this.accounts = accounts == null ? List.of() : List.copyOf(accounts);
        this.passportBase = passportBase;
    }

    public StartResult start(String requestedAccount) {
        BiliVideoReadTool.BiliVideoAccount account = select(requestedAccount);
        cleanExpired();
        try (BilibiliLoginClient client = client()) {
            BilibiliLoginClient.QrStart qr = client.start();
            String loginId = "bili-login-" + UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(TTL);
            pending.put(loginId, new Pending(account, qr.qrcodeKey(), expiresAt));
            return new StartResult(loginId, account.accountId(), qr.url(),
                    TTL.toSeconds());
        }
    }

    public PollResult poll(String loginId) {
        Pending current = pending.get(loginId);
        if (current == null) {
            return new PollResult("UNKNOWN", "登录会话不存在或已结束", "");
        }
        if (Instant.now().isAfter(current.expiresAt())) {
            pending.remove(loginId);
            return new PollResult("EXPIRED", "二维码已失效", current.account().accountId());
        }
        try (BilibiliLoginClient client = client()) {
            BilibiliLoginClient.QrPoll poll = client.poll(current.qrcodeKey());
            if (poll.state() == BilibiliLoginClient.QrState.SUCCESS) {
                writeCredential(current.account().credentialFile(), poll.credential());
                pending.remove(loginId);
                return new PollResult("SUCCESS", "B站账号登录成功",
                        current.account().accountId());
            }
            if (poll.state() == BilibiliLoginClient.QrState.EXPIRED
                    || poll.state() == BilibiliLoginClient.QrState.FAILED) {
                pending.remove(loginId);
            }
            return new PollResult(poll.state().name(), poll.message(),
                    current.account().accountId());
        }
    }

    private BiliVideoReadTool.BiliVideoAccount select(String requestedAccount) {
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("没有配置 B 站账号");
        }
        if (requestedAccount == null || requestedAccount.isBlank()) {
            return accounts.getFirst();
        }
        return accounts.stream()
                .filter(account -> requestedAccount.equals(account.accountId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未配置该 B 站账号: " + requestedAccount));
    }

    private BilibiliLoginClient client() {
        return passportBase == null || passportBase.isBlank()
                ? new BilibiliLoginClient() : new BilibiliLoginClient(passportBase);
    }

    private void cleanExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    private static void writeCredential(Path target, BilibiliCredential credential)
            throws IllegalStateException {
        Objects.requireNonNull(credential, "credential");
        Path absolute = target.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent == null) throw new IOException("凭据文件缺少父目录");
            Files.createDirectories(parent);
            secureDirectory(parent);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sessdata", credential.sessdata());
            values.put("biliJct", credential.biliJct());
            values.put("buvid3", credential.buvid3());
            values.put("buvid4", credential.buvid4());
            values.put("dedeUserId", credential.dedeUserId());
            byte[] bytes = JSON.writeValueAsString(values)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path temporary = Files.createTempFile(parent, ".bili-credential-", ".tmp");
            try {
                secureFile(temporary);
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, absolute,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                secureFile(absolute);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("B站登录凭据保存失败", failure);
        }
    }

    private static void secureDirectory(Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统尽力而为。
        }
    }

    private static void secureFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统尽力而为。
        }
    }

    private record Pending(BiliVideoReadTool.BiliVideoAccount account,
                           String qrcodeKey, Instant expiresAt) {
    }

    public record StartResult(String loginId, String accountId, String qrUrl,
                              long expiresInSeconds) {
    }

    public record PollResult(String state, String message, String accountId) {
    }
}
