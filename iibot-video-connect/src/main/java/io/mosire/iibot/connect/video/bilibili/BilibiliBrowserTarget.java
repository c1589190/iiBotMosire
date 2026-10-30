package io.mosire.iibot.connect.video.bilibili;

import io.mosire.iibot.connect.video.browser.BrowserFrameRequest;
import io.mosire.iibot.connect.video.browser.BrowserTarget;

import java.net.URI;

/** B 站视频页目标构造器；只接受 BV 号与分P/时间。 */
public final class BilibiliBrowserTarget implements BrowserTarget {
    private static final String BASE = "https://www.bilibili.com/video/";

    @Override
    public URI uriFor(BrowserFrameRequest request) {
        long seconds = Math.max(0L, (long) Math.floor(request.timestampSeconds()));
        return URI.create(BASE + request.bvid() + "?p=" + request.page()
                + "&t=" + seconds + "&autoplay=1");
    }
}
