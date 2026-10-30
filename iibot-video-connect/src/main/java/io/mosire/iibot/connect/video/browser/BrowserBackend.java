package io.mosire.iibot.connect.video.browser;

public interface BrowserBackend extends AutoCloseable {
    BrowserSession open(BrowserProfile profile);

    default BrowserFrameResult captureFrame(BrowserProfile profile, BrowserFrameRequest request) {
        try (BrowserSession session = open(profile)) {
            return session.capture(request);
        }
    }

    @Override
    void close();
}
