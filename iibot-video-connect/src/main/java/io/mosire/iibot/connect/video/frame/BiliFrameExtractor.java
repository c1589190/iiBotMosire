package io.mosire.iibot.connect.video.frame;

import java.nio.file.Path;

/** 可替换的视频抽帧后端；默认实现使用本机 ffmpeg。 */
public interface BiliFrameExtractor {

    Frame extract(Path videoFile, double timestampSeconds,
                  Path outputFile) throws Exception;

    record Frame(Path file, int width, int height) {
        public Frame {
            file = file.toAbsolutePath().normalize();
        }
    }
}
