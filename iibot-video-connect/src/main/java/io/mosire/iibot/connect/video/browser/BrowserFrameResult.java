package io.mosire.iibot.connect.video.browser;

import java.nio.file.Path;

public record BrowserFrameResult(Path file, int width, int height, String backend) {
}
