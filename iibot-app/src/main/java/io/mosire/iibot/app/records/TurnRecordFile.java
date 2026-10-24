package io.mosire.iibot.app.records;

import java.util.Objects;

/** 一个回合 Markdown 文件的查询结果；file 为安全相对名，不返回物理路径。 */
public record TurnRecordFile(
        String turnId,
        String file,
        String content,
        String contentHash) {

    public TurnRecordFile {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        Objects.requireNonNull(content, "content");
        contentHash = contentHash == null ? "" : contentHash;
    }

    @Override
    public String toString() {
        return "TurnRecordFile[turnId=" + turnId
                + ", file=" + file
                + ", contentLength=" + content.length()
                + ", contentHash=" + contentHash
                + ']';
    }
}
