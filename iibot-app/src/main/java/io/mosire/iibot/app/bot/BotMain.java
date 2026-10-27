package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.ConfigArguments;
import io.mosire.iibot.app.config.ConfigException;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.inbox.BotInboxRuntimeOptions;
import io.mosire.iibot.connect.napcat.NapCatAdapter;
import io.mosire.iibot.connect.napcat.NapCatConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Bot-first CLI：{@code java -jar iibot-app.jar [--config <file-or-dir>]...}。
 *
 * <p>Bot 是默认运行模式，不再需要 {@code --mode bot}；legacy 模式由
 * {@code --mode legacy} 显式选择。多个配置源按参数顺序合并，目录源读取其中
 * 直接子级 {@code *.json} 并按文件名排序。</p>
 */
public final class BotMain {

    private static final Logger LOG = LoggerFactory.getLogger(BotMain.class);
    private static final Path DEFAULT_CONFIG = Path.of("config", "bot");

    private BotMain() {
    }

    public static void main(String[] args) {
        run(args);
    }

    public static void run(String[] args) {
        ConfigArguments.ParseResult parsed;
        try {
            parsed = ConfigArguments.parse(ConfigArguments.withoutMode(args), DEFAULT_CONFIG);
        } catch (ConfigException failure) {
            LOG.error("参数错误: {}", failure.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        if (parsed.helpRequested()) {
            printUsage();
            return;
        }
        run(parsed.sources(), !parsed.explicitConfig());
    }

    /** 用已解析的配置源启动 bot，不触发首次配置生成。 */
    public static void run(List<Path> configSources) {
        run(configSources, false);
    }

    /**
     * 用配置源启动 bot；默认入口在没有任何显式 --config 时允许首次生成模板。
     * 首次生成模板后直接退出，不启动 QQ/Inbox/MCP。
     */
    public static void run(List<Path> configSources, boolean bootstrapDefaultIfMissing) {
        BotRuntime runtime = null;
        try {
            if (configSources == null || configSources.isEmpty()) {
                throw new ConfigException("至少需要一个配置源");
            }
            if (bootstrapDefaultIfMissing && configSources.size() == 1) {
                BotConfigBootstrap.Result bootstrap =
                        BotConfigBootstrap.ensureDefault(configSources.getFirst());
                if (bootstrap.generated()) {
                    printBootstrapNotice(bootstrap);
                    System.exit(2);
                    return;
                }
            }
            BotConfig config = BotConfigLoader.load(configSources);
            SecretProvider secrets = SecretProvider.environment();
            String controlToken = secrets.lookup(config.control().bearerTokenEnv())
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalStateException(
                            "Bot MCP token 未配置：环境变量 "
                                    + config.control().bearerTokenEnv() + " 为空"));
            String napcatToken = config.napcat().accessTokenEnv()
                    .map(env -> secrets.lookup(env).orElseThrow(() -> new IllegalStateException(
                            "NapCat token 未配置：环境变量 " + env + " 为空")))
                    .orElse(null);
            NapCatConfig napcat = napCatConfig(config, napcatToken);
            NapCatAdapter adapter = new NapCatAdapter(napcat);
            BotInboxRuntimeOptions inboxOptions = new BotInboxRuntimeOptions(
                    config.inbox().dataDir(),
                    config.inbox().inboxId(),
                    config.inbox().bindingLabel(),
                    config.inbox().channels(),
                    config.inbox().bindingTtlHours(),
                    config.inbox().apiHost(),
                    config.inbox().apiPort(),
                    config.inbox().mcpHost(),
                    config.inbox().mcpPort(),
                    config.inbox().mcpPath(),
                    Duration.ofMillis(config.inbox().heartbeatMillis()),
                    Duration.ofMillis(config.inbox().claimSweepIntervalMillis()));
            BotRuntimeOptions options = new BotRuntimeOptions(
                    config.dataDir(),
                    config.accountId(),
                    config.control().host(),
                    config.control().port(),
                    config.control().path(),
                    controlToken,
                    inboxOptions,
                    config.summary().debounceMillis(),
                    config.summary().maxMessagesPerDigest(),
                    Duration.ofSeconds(config.outbox().sendTimeoutSeconds()),
                    config.napcat().privateTempGroupId().orElse(null),
                    Duration.ofMillis(config.outbox().minSendIntervalMillis()),
                    config.weighting().toSettings());
            runtime = BotRuntime.start(adapter, options);
            BotRuntime shutdownTarget = runtime;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(shutdownTarget::close, "iibot-bot-shutdown"));
            LOG.info("iiBot bot-first 已启动。按 Ctrl+C 退出。");
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.io.IOException startupFailure) {
            LOG.error("bot-first 启动失败: {}", startupFailure.getMessage());
            if (runtime != null) {
                runtime.close();
            }
            System.exit(1);
        }
    }

    private static NapCatConfig napCatConfig(BotConfig config, String accessToken) {
        BotConfig.NapCat source = config.napcat();
        NapCatConfig.Builder builder = NapCatConfig.builder(
                        source.eventWebSocketUri(), source.httpBaseUri())
                .accountId(source.accountId().orElse(config.accountId()))
                .requestTimeout(Duration.ofSeconds(source.requestTimeoutSeconds()))
                .maxTextLength(source.maxTextLength())
                .privateTempGroupId(source.privateTempGroupId().orElse(null))
                .suppressSelfMessages(source.suppressSelfMessages());
        if (accessToken != null) {
            builder.accessToken(accessToken);
        }
        return builder.build();
    }

    private static void printBootstrapNotice(BotConfigBootstrap.Result result) {
        LOG.warn("首次启动：未发现配置，已生成模板目录 {}", result.directory());
        System.err.println("============================================================");
        System.err.println("首次启动：已生成 bot 配置模板：");
        for (Path file : result.files()) {
            System.err.println("  " + file);
        }
        System.err.println("请至少修改：");
        System.err.println("  config/bot/00-base.json      accountId");
        System.err.println("  config/bot/10-napcat.json    WS/HTTP URI 和 token 环境变量名");
        System.err.println("  config/bot/20-control-inbox.json  MCP 端口/path 和 Inbox id");
        System.err.println("然后设置 IIBOT_NAPCAT_ACCESS_TOKEN 和 IIBOT_CONTROL_TOKEN，");
        System.err.println("再次运行: java -jar " + jarHint());
        System.err.println("本次不会启动 QQ/Inbox/MCP。");
        System.err.println("============================================================");
    }

    private static String jarHint() {
        return "iibot-app-0.1.0-SNAPSHOT.jar";
    }

    private static void printUsage() {
        System.err.println("用法: java -jar iibot-app.jar [--config <file-or-dir>]...");
        System.err.println("      默认 bot 模式；省略配置时读取 config/bot/。");
        System.err.println("      首次没有配置时会生成 config/bot/ 模板并退出。");
        System.err.println("      同一配置目录下的多个 JSON 按文件名排序合并。");
    }
}
