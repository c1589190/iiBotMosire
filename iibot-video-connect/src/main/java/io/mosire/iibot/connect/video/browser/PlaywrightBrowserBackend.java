package io.mosire.iibot.connect.video.browser;

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
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Playwright embedded 浏览器后端：启动系统 Chromium，按 B 站页面脚本 seek 后截取 JPEG。
 *
 * <p>后端是懒加载的；构造对象不会启动浏览器。生产只允许 B 站域名，测试可注入
 * loopback fake 页面策略。错误消息只暴露稳定 code，不带 URL/HTML/凭据。</p>
 */
public final class PlaywrightBrowserBackend implements BrowserBackend {
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
    private final Path executablePath;
    private final int maxConcurrentSessions;
    private final Semaphore permits;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private Playwright playwright;
    private boolean closed;

    public PlaywrightBrowserBackend(BrowserTarget target, BrowserPolicy policy,
                                    Path executablePath, int maxConcurrentSessions) {
        this.target = Objects.requireNonNull(target, "target");
        this.guard = new BrowserGuard(Objects.requireNonNull(policy, "policy"));
        this.executablePath = executablePath == null ? null : executablePath.toAbsolutePath().normalize();
        if (maxConcurrentSessions < 1 || maxConcurrentSessions > 8) {
            throw new IllegalArgumentException("maxConcurrentSessions 必须在 1..8");
        }
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.permits = new Semaphore(maxConcurrentSessions, true);
    }

    public Path executablePath() {
        return executablePath;
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
            BrowserSession session = launch(profile);
            OpsLog.debug("event=video_browser_open result=ok site={} headless={}",
                    profile.site(), profile.headless());
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException("BROWSER_INTERRUPTED", "浏览器启动被中断", e);
        } catch (BrowserException e) {
            if (acquired) permits.release();
            OpsLog.warn("event=video_browser_open result=fail code={}", e.code());
            throw e;
        } catch (PlaywrightException e) {
            if (acquired) permits.release();
            OpsLog.warn("event=video_browser_open result=fail code=BROWSER_LAUNCH_FAILED");
            throw new BrowserException("BROWSER_LAUNCH_FAILED", "浏览器启动失败", e);
        } catch (IOException e) {
            if (acquired) permits.release();
            throw new BrowserException("BROWSER_PROFILE_FAILED", "浏览器 profile 创建失败", e);
        }
    }

    private BrowserSession launch(BrowserProfile profile) throws IOException {
        Files.createDirectories(profile.profileDir());
        try {
            Files.setPosixFilePermissions(profile.profileDir(),
                    PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统由上层权限控制。
        }
        Playwright p = playwright();
        BrowserType.LaunchPersistentContextOptions options =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(profile.headless())
                        .setViewportSize(profile.viewportWidth(), profile.viewportHeight())
                        .setLocale(profile.locale())
                        .setTimeout(profile.timeoutSeconds() * 1000.0)
                        .setArgs(List.of("--disable-dev-shm-usage"));
        Path executable = executablePath == null ? findChromium() : executablePath;
        if (executable != null) options.setExecutablePath(executable);
        BrowserContext context = p.chromium().launchPersistentContext(profile.profileDir(), options);
        Session session = new Session(context, profile, permits);
        sessions.add(session);
        return session;
    }

    private synchronized Playwright playwright() {
        if (playwright == null) playwright = Playwright.create();
        return playwright;
    }

    private static Path findChromium() {
        String configured = System.getenv("IIBOT_CHROMIUM");
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured);
        candidates.addAll(List.of("/snap/bin/chromium", "/usr/bin/chromium",
                "/usr/bin/chromium-browser", "/usr/bin/google-chrome"));
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) return path;
        }
        return null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Session session : sessions) {
            session.close();
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
        OpsLog.info("event=video_browser_close result=ok");
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
            context.setDefaultTimeout(profile.timeoutSeconds() * 1000.0);
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
                                + "timestamp={} bytes={} durationMs={}",
                        request.bvid(), request.page(), request.timestampSeconds(),
                        jpeg.length, (System.nanoTime() - startedNanos) / 1_000_000);
                return new BrowserFrameResult(request.outputFile(), image.getWidth(),
                        image.getHeight(), "playwright-embedded");
            } catch (BrowserException e) {
                OpsLog.warn("event=video_browser_capture result=fail code={} bvid={} page={}",
                        e.code(), request.bvid(), request.page());
                throw e;
            } catch (PlaywrightException e) {
                OpsLog.warn("event=video_browser_capture result=fail "
                                + "code=BROWSER_CAPTURE_FAILED bvid={} page={}",
                        request.bvid(), request.page());
                throw new BrowserException("BROWSER_CAPTURE_FAILED", "浏览器抽帧失败", e);
            } catch (IOException e) {
                OpsLog.warn("event=video_browser_capture result=fail "
                                + "code=BROWSER_FRAME_WRITE_FAILED bvid={} page={}",
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
