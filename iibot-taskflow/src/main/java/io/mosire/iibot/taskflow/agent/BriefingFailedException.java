package io.mosire.iibot.taskflow.agent;

/** 秘书 brief 流程的具名失败；code 只使用稳定错误标识，message 可安全回灌给模型。 */
public final class BriefingFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public BriefingFailedException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
