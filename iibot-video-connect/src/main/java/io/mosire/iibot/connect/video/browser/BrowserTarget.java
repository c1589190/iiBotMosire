package io.mosire.iibot.connect.video.browser;

import java.net.URI;

/** 由受控代码把视频请求映射为页面地址；测试可注入本地 fake 页面。 */
@FunctionalInterface
public interface BrowserTarget {
    URI uriFor(BrowserFrameRequest request);
}
