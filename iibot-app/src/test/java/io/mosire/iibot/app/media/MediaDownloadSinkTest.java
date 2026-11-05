package io.mosire.iibot.app.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaDownloadSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsTraversalAbsoluteAndDriveLetterPaths() {
        MediaDownloadSink sink = new MediaDownloadSink(tempDir);
        byte[] bytes = {1, 2, 3};

        assertThatThrownBy(() -> sink.save("../../etc/passwd", "x.png", bytes))
                .isInstanceOf(MediaDownloadSink.InvalidSaveToException.class);
        assertThatThrownBy(() -> sink.save("a/../../b.png", "x.png", bytes))
                .isInstanceOf(MediaDownloadSink.InvalidSaveToException.class);
        assertThatThrownBy(() -> sink.save("/etc/passwd", "x.png", bytes))
                .isInstanceOf(MediaDownloadSink.InvalidSaveToException.class);
        assertThatThrownBy(() -> sink.save("C:\\windows\\x.png", "x.png", bytes))
                .isInstanceOf(MediaDownloadSink.InvalidSaveToException.class);
    }

    @Test
    void rejectionMessagesDoNotLeakInternalPaths() {
        MediaDownloadSink sink = new MediaDownloadSink(tempDir);

        assertThatThrownBy(() -> sink.validate("../../etc/passwd"))
                .isInstanceOf(MediaDownloadSink.InvalidSaveToException.class)
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining("downloads/");
    }

    @Test
    void savesInsideDownloadsWithExactBytes() throws Exception {
        MediaDownloadSink sink = new MediaDownloadSink(tempDir);
        byte[] bytes = {9, 8, 7, 6};

        Path saved = sink.save("pics/report.png", "ignored.png", bytes);

        assertThat(saved.isAbsolute()).isTrue();
        assertThat(saved).startsWith(
                tempDir.toAbsolutePath().normalize().resolve(MediaDownloadSink.DIRECTORY_NAME));
        assertThat(saved.getFileName().toString()).isEqualTo("report.png");
        assertThat(Files.readAllBytes(saved)).containsExactly(bytes);
    }

    @Test
    void trailingSlashAndExistingDirectoryUseFallbackNameAndDedupe() throws Exception {
        MediaDownloadSink sink = new MediaDownloadSink(tempDir);
        byte[] bytes = {1, 1, 2, 3, 5};

        Path first = sink.save("out/", "qq-m-1-0.png", bytes);
        Path second = sink.save("out", "qq-m-1-0.png", bytes);
        Path third = sink.save("out", "qq-m-1-0.png", bytes);

        Path directory = tempDir.resolve(MediaDownloadSink.DIRECTORY_NAME).resolve("out");
        assertThat(first).isEqualTo(directory.resolve("qq-m-1-0.png"));
        assertThat(second).isEqualTo(directory.resolve("qq-m-1-0-1.png"));
        assertThat(third).isEqualTo(directory.resolve("qq-m-1-0-2.png"));
        assertThat(Files.readAllBytes(second)).containsExactly(bytes);
    }

    @Test
    void defaultFileNameSanitizesMessageKeyAndMapsMediaType() {
        assertThat(MediaDownloadSink.defaultFileName("a/b:c", 2, "image/jpeg"))
                .isEqualTo("qq-a-b-c-2.jpg");
        assertThat(MediaDownloadSink.defaultFileName("", 0, "audio/mpeg"))
                .isEqualTo("qq-media-0.mp3");
    }
}
