package io.mosire.iibot.app.bilibili;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 使用本机 ffmpeg 从 B 站 DASH/MP4 地址抓取单帧。 */
public final class FfmpegBiliFrameExtractor implements BiliFrameExtractor {

    private final String ffmpeg;
    private final int maxWidth;
    private final Duration timeout;

    public FfmpegBiliFrameExtractor() {
        this(System.getenv().getOrDefault("IIBOT_FFMPEG", "ffmpeg"),
                1280, Duration.ofSeconds(45));
    }

    public FfmpegBiliFrameExtractor(String ffmpeg, int maxWidth, Duration timeout) {
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg.trim();
        this.maxWidth = Math.max(320, Math.min(maxWidth, 1920));
        this.timeout = timeout == null ? Duration.ofSeconds(45) : timeout;
    }

    @Override
    public Frame extract(Path videoFile, double timestampSeconds,
                         Path outputFile) throws Exception {
        Files.createDirectories(outputFile.getParent());
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-ss");
        command.add(String.format(java.util.Locale.ROOT, "%.3f", timestampSeconds));
        command.add("-i");
        command.add(videoFile.toAbsolutePath().normalize().toString());
        command.add("-frames:v");
        command.add("1");
        command.add("-an");
        command.add("-sn");
        command.add("-vf");
        command.add("scale='min(" + maxWidth + ",iw)':-2");
        command.add("-q:v");
        command.add("3");
        command.add("-y");
        command.add(outputFile.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg 抽帧超时");
            }
            output = readLimited(process.getInputStream(), 16 * 1024);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("ffmpeg 抽帧被中断");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(outputFile)) {
            throw new IOException("ffmpeg 抽帧失败: " + safeOutput(output));
        }
        byte[] bytes = Files.readAllBytes(outputFile);
        if (bytes.length < 3 || (bytes[0] & 0xFF) != 0xFF
                || (bytes[1] & 0xFF) != 0xD8 || (bytes[2] & 0xFF) != 0xFF) {
            throw new IOException("ffmpeg 输出不是 JPEG");
        }
        return new Frame(outputFile, 0, 0);
    }

    private static String readLimited(java.io.InputStream input, int limit)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0 && total < limit) {
            int remaining = limit - total;
            int length = Math.min(read, remaining);
            output.write(buffer, 0, length);
            total += length;
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String safeOutput(String output) {
        String value = output == null ? "" : output.trim();
        value = value.replaceAll("(?i)https?://\\S+", "<url>");
        return value.length() > 240 ? value.substring(0, 240) : value;
    }
}
