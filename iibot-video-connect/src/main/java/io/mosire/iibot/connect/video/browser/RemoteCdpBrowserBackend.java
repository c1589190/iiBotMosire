package io.mosire.iibot.connect.video.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ScreenshotType;
import io.mosire.iibot.connect.ops.OpsLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Playwright remote-cdp 后端：连接本机/同 Pod 回环 CDP sidecar，按 B 站页面脚本
 * seek 后截取 JPEG。CDP endpoint 只允许回环 host；连接懒建立，错误不泄露 endpoint。
 */
public final class RemoteCdpBrowserBackend implements BrowserBackend {
    private static final String SEEK_SCRIPT = """
            (args) => {
              const el = document.querySelector(args.selector);
              if (!el) throw new Error("TARGET_NOT_FOUND");
              if (el.tagName === "VIDEO") {
                el.pause();
                el.currentTime = args.timestamp;
              }
              return true;
            }
            """;
    private static final String READY_SCRIPT = """
            (args) => {
              const el = document.querySelector(args.selector);
              if (!el) return false;
              if (el.tagName !== "VIDEO") return true;
              return !el.seeking && el.readyState >= 2;
            }
            """;

    private final BrowserTarget target;
    private final BrowserGuard guard;
    private final URI endpoint;
    private final Semaphore permits;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private Playwright playwright;
    private Browser browser;
    private boolean closed;

    public RemoteCdpBrowserBackend(BrowserTarget target, BrowserPolicy policy,
                                   String cdpEndpoint, int maxConcurrentSessions) {
        this.target = Objects.requireNonNull(target, "target");
        this.guard = new BrowserGuard(Objects.requireNonNull(policy, "policy"));
        this.endpoint = CdpEndpoint.parse(cdpEndpoint).uri();
        if (maxConcurrentSessions < 1 || maxConcurrentSessions > 8) {
            throw new IllegalArgumentException("maxConcurrentSessions 必须在 1..8");
        }
        this.permits = new Semaphore(maxConcurrentSessions, true);
    }

    @Override
    public BrowserSession open(BrowserProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (closed) throw new BrowserException("BROWSER_CLOSED", "浏览器后端已关闭");
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(profile.timeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                throw new BrowserException("BROWSER_BUSY", "浏览器并发已满");
            }
            BrowserSession session = connect(profile);
            OpsLog.debug("event=video_browser_open result=ok site={} mode=remote-cdp",
                    profile.site());
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException("BROWSER_INTERRUPTED", "浏览器启动被中断", e);
        } catch (BrowserException e) {
            if (acquired) permits.release();
            OpsLog.warn("event=video_browser_open result=fail code={} mode=remote-cdp", e.code());
            throw e;
        } catch (PlaywrightException e) {
            if (acquired) permits.release();
            OpsLog.warn("event=video_browser_open result=fail "
                    + "code=BROWSER_CDP_CONNECT_FAILED mode=remote-cdp");
            throw new BrowserException("BROWSER_CDP_CONNECT_FAILED", "浏览器 CDP 连接失败", e);
        } catch (RuntimeException e) {
            if (acquired) permits.release();
            OpsLog.warn("event=video_browser_open result=fail "
                    + "code=BROWSER_CDP_CONNECT_FAILED mode=remote-cdp");
            throw new BrowserException("BROWSER_CDP_CONNECT_FAILED", "浏览器 CDP 连接失败", e);
        }
    }

    private BrowserSession connect(BrowserProfile profile) {
        Playwright p = playwright();
        Browser b = browser(p, profile);
        BrowserContext context = b.newContext(new Browser.NewContextOptions()
                .setViewportSize(profile.viewportWidth(), profile.viewportHeight())
                .setLocale(profile.locale()));
        context.setDefaultTimeout(profile.timeoutSeconds() * 1000.0);
        Session session = new Session(context, profile, permits);
        sessions.add(session);
        return session;
    }

    private synchronized Playwright playwright() {
        if (playwright == null) playwright = Playwright.create();
        return playwright;
    }

    private synchronized Browser browser(Playwright p, BrowserProfile profile) {
        if (browser == null) {
            browser = p.chromium().connectOverCDP(endpoint.toString(),
                    new BrowserType.ConnectOverCDPOptions()
                            .setTimeout(profile.timeoutSeconds() * 1000.0));
        }
        return browser;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Session session : sessions) {
            session.close();
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (PlaywrightException ignored) {
                // 连接关闭失败不覆盖原始结果。
            }
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
        OpsLog.info("event=video_browser_close result=ok mode=remote-cdp");
    }

    private final class Session implements BrowserSession {
        private final BrowserContext context;
        private final BrowserProfile profile;
        private final Semaphore owner;
        private boolean closedSession;

        private Session(BrowserContext context, BrowserProfile profile, Semaphore owner) {
            this.context = context;
            this.profile = profile;
            this.owner = owner;
        }

        @Override
        public BrowserFrameResult capture(BrowserFrameRequest request) {
            Objects.requireNonNull(request, "request");
            URI uri = guard.validate(target.uriFor(request).toString());
            long startedNanos = System.nanoTime();
            try {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                page.navigate(uri.toString(),
                        new Page.NavigateOptions().setTimeout(profile.timeoutSeconds() * 1000.0));
                page.waitForSelector(request.selector(),
                        new Page.WaitForSelectorOptions().setTimeout(profile.timeoutSeconds() * 1000.0));
                page.evaluate(SEEK_SCRIPT, Map.of(
                        "selector", request.selector(),
                        "timestamp", request.timestampSeconds()));
                page.waitForFunction(READY_SCRIPT, Map.of("selector", request.selector()),
                        new Page.WaitForFunctionOptions().setTimeout(profile.timeoutSeconds() * 1000.0));
                byte[] jpeg = page.locator(request.selector()).screenshot(
                        new Locator.ScreenshotOptions()
                                .setType(ScreenshotType.JPEG)
                                .setQuality(85)
                                .setTimeout(profile.timeoutSeconds() * 1000.0));
                if (jpeg.length == 0) {
                    throw new BrowserException("BROWSER_EMPTY_FRAME", "浏览器截图为空");
                }
                BufferedImage image;
                try {
                    image = ImageIO.read(new ByteArrayInputStream(jpeg));
                } catch (IOException e) {
                    throw new BrowserException("BROWSER_INVALID_FRAME", "浏览器截图不是有效图片", e);
                }
                if (image == null) {
                    throw new BrowserException("BROWSER_INVALID_FRAME", "浏览器截图不是有效图片");
                }
                Files.createDirectories(request.outputFile().getParent());
                Files.write(request.outputFile(), jpeg);
                OpsLog.debug("event=video_browser_capture result=ok bvid={} page={} "
                                + "timestamp={} bytes={} durationMs={} mode=remote-cdp",
                        request.bvid(), request.page(), request.timestampSeconds(),
                        jpeg.length, (System.nanoTime() - startedNanos) / 1_000_000);
                return new BrowserFrameResult(request.outputFile(), image.getWidth(),
                        image.getHeight(), "playwright-remote-cdp");
            } catch (BrowserException e) {
                OpsLog.warn("event=video_browser_capture result=fail code={} bvid={} page={} "
                        + "mode=remote-cdp", e.code(), request.bvid(), request.page());
                throw e;
            } catch (PlaywrightException e) {
                OpsLog.warn("event=video_browser_capture result=fail "
                                + "code=BROWSER_CAPTURE_FAILED bvid={} page={} mode=remote-cdp",
                        request.bvid(), request.page());
                throw new BrowserException("BROWSER_CAPTURE_FAILED", "浏览器抽帧失败", e);
            } catch (IOException e) {
                OpsLog.warn("event=video_browser_capture result=fail "
                                + "code=BROWSER_FRAME_WRITE_FAILED bvid={} page={} mode=remote-cdp",
                        request.bvid(), request.page());
                throw new BrowserException("BROWSER_FRAME_WRITE_FAILED", "浏览器截图写入失败", e);
            }
        }

        @Override
        public void close() {
            if (closedSession) return;
            closedSession = true;
            try {
                context.close();
            } catch (PlaywrightException ignored) {
                // 关闭失败不覆盖原始结果。
            } finally {
                sessions.remove(this);
                owner.release();
            }
        }
    }
}
