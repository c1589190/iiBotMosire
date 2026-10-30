package io.mosire.iibot.connect.video.browser;

public interface BrowserSession extends AutoCloseable {
    BrowserFrameResult capture(BrowserFrameRequest request);

    @Override
    void close();
}
