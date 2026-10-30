package io.mosire.iibot.connect.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 可选群文件能力。实现只接受已加入群的 typed 参数；不允许调用方传 URL、路径或
 * 任意 OneBot action。
 */
public interface GroupFileCapability {

    /**
     * 列出群文件目录。
     *
     * @param groupId 群标识
     * @param folderId 目录标识；空值由实现归一为根目录
     */
    CompletionStage<List<QqGroupFile>> groupFiles(String groupId, String folderId);

    /**
     * 通过 provider 的受控下载路径取回一个群文件。
     *
     * @param groupId 群标识
     * @param file 之前从 {@link #groupFiles(String, String)} 得到的内部元数据
     */
    CompletionStage<MediaAsset> downloadGroupFile(String groupId, QqGroupFile file);
}
