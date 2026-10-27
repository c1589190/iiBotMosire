package io.mosire.iibot.connect.core;

import java.util.Arrays;
import java.util.Objects;

/** 适配器取回的一张图片；字节不进入日志/摘要/MCP 文本。 */
public record MediaAsset(String fileName, String mediaType, byte[] bytes) {

    public MediaAsset {
        Objects.requireNonNull(fileName, "fileName");
        mediaType = mediaType == null || mediaType.isBlank()
                ? "application/octet-stream" : mediaType.trim();
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("媒体字节不能为空");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return "MediaAsset{fileName=" + fileName + ", mediaType=" + mediaType
                + ", size=" + bytes.length + '}';
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MediaAsset asset
                && fileName.equals(asset.fileName)
                && mediaType.equals(asset.mediaType)
                && Arrays.equals(bytes, asset.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * fileName.hashCode() + mediaType.hashCode())
                + Arrays.hashCode(bytes);
    }
}
