package io.mosire.iibot.app;

import io.mosire.iibot.app.bot.BotMain;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.AppConfigLoader;
import io.mosire.iibot.app.config.ConfigArguments;
import io.mosire.iibot.app.config.ConfigException;
import io.mosire.iibot.app.runtime.AppBootstrap;
import io.mosire.iibot.app.runtime.AppRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * iiBotMosire 可运行入口。
 *
 * <p>默认启动 bot-first；legacy 文游组合根必须显式传 {@code --mode legacy}。
 * 配置源可重复传多个 {@code --config <file-or-dir>}，省略时分别读取
 * {@code config/bot/} 或 {@code config/legacy/}。</p>
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final Path DEFAULT_BOT_CONFIG = Path.of("config", "bot");
    private static final Path DEFAULT_LEGACY_CONFIG = Path.of("config", "legacy");

    private Main() {
    }

    public static void main(String[] args) {
        String mode;
        ConfigArguments.ParseResult cli;
        try {
            mode = parseMode(args);
            Path defaultConfig = "legacy".equals(mode)
                    ? DEFAULT_LEGACY_CONFIG : DEFAULT_BOT_CONFIG;
            cli = ConfigArguments.parse(ConfigArguments.withoutMode(args), defaultConfig);
        } catch (ConfigException failure) {
            LOG.error("参数错误: {}", failure.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        if (cli.helpRequested()) {
            printUsage();
            return;
        }
        if ("bot".equals(mode)) {
            BotMain.run(cli.sources(), !cli.explicitConfig());
            return;
        }

        AppRuntime runtime = null;
        try {
            AppConfig config = AppConfigLoader.load(cli.sources());
            runtime = AppBootstrap.build(config);
            AppRuntime shutdownTarget = runtime;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(shutdownTarget::close, "iibot-shutdown"));
            runtime.start();
            LOG.info("iiBotMosire legacy 已启动。按 Ctrl+C 退出。");
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException startupFailure) {
            LOG.error("启动失败: {}", startupFailure.getMessage());
            if (runtime != null) {
                runtime.close();
            }
            System.exit(1);
        }
    }

    /** 无 --mode 时默认 bot；显式 bot 仅保留兼容。 */
    static String parseMode(String[] args) {
        if (args == null) {
            return "bot";
        }
        String mode = "bot";
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--mode".equals(arg)) {
                if (index + 1 >= args.length || args[index + 1].isBlank()) {
                    throw new ConfigException("--mode 需要一个值");
                }
                mode = args[++index];
            } else if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length());
            }
        }
        if (!"bot".equals(mode) && !"legacy".equals(mode)) {
            throw new ConfigException("--mode 只允许 bot 或 legacy");
        }
        return mode;
    }

    private static void printUsage() {
        System.err.println("用法: java -jar iibot-app.jar [--config <file-or-dir>]...");
        System.err.println("      默认 bot 模式；配置省略时读取 config/bot/。");
        System.err.println("      legacy 需要显式传 --mode legacy，默认读取 config/legacy/。");
    }
}
