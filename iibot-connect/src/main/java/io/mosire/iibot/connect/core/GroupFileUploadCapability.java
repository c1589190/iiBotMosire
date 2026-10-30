package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/** 可选群文件上传能力；只接受服务端已受控的字节资产和群目标。 */
public interface GroupFileUploadCapability {

    CompletionStage<SendReceipt> uploadGroupFile(String groupId, String fileName,
                                                 String mediaType, byte[] bytes,
                                                 String idempotencyKey);
}
