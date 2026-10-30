package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;

/**
 * 群文件目录中的一条内部元数据。
 *
 * <p>{@code fileId}、{@code busid} 和下载 URL 都不进入 Agent 可见结果；上层 MCP
 * 只会为当前进程生成临时 fileRef。{@link #toString()} 不输出内部定位符。</p>
 */
public record QqGroupFile(
        String fileId,
        String name,
        long byteSize,
        String uploaderId,
        String uploaderName,
        Instant uploadedAt,
        String busid) {

    public QqGroupFile {
        fileId = requireText(fileId, "fileId");
        name = optional(name);
        if (name.isEmpty()) {
            name = "未命名文件";
        }
        if (byteSize < 0L) {
            throw new IllegalArgumentException("byteSize 不能为负");
        }
        uploaderId = optional(uploaderId);
        uploaderName = optional(uploaderName);
        busid = optional(busid);
        if (busid.isEmpty()) {
            busid = "102";
        }
    }

    public QqGroupFile(String fileId, String name, long byteSize,
                       String uploaderId, String uploaderName, Instant uploadedAt) {
        this(fileId, name, byteSize, uploaderId, uploaderName, uploadedAt, "102");
    }

    @Override
    public String toString() {
        return "QqGroupFile{name=" + name
                + ", byteSize=" + byteSize
                + ", uploader=" + (uploaderName.isEmpty() ? "<unknown>" : uploaderName)
                + ", uploadedAt=" + uploadedAt
                + '}';
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
