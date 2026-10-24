package io.mosire.iibot.taskflow.cache;

import java.util.Objects;

/** 秘书 cache 边界的稳定错误类型；code 可安全回给工具调用方。 */
public final class SecretaryCacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CACHE_UNAVAILABLE = "CACHE_UNAVAILABLE";
    public static final String CACHE_NOT_FOUND = "CACHE_NOT_FOUND";
    public static final String CACHE_OWNER_MISMATCH = "CACHE_OWNER_MISMATCH";
    public static final String CACHE_WRITE_FAILED = "CACHE_WRITE_FAILED";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    private final String code;

    public SecretaryCacheException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public SecretaryCacheException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
