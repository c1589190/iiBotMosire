package io.mosire.iibot.taskflow.workspace;

/**
 * GM 私有文档区的稳定失败类型。
 *
 * <p>异常消息只描述稳定原因，不包含文档正文或物理路径。{@link #code()} 供 MCP 工具
 * 映射为稳定的外部错误码。</p>
 */
public final class GmPrivateDocsException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String CONFLICT = "CONFLICT";
    public static final String NOT_FOUND = "GM_DOC_NOT_FOUND";
    public static final String UNAVAILABLE = "GM_DOCS_UNAVAILABLE";

    private final String code;

    public GmPrivateDocsException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? UNAVAILABLE : code;
    }

    public String code() {
        return code;
    }
}
