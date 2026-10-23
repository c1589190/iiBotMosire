package io.mosire.iibot.app;

import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.AppConfigLoader;
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
 * <p>用法：{@code java -jar iibot-app.jar --config config/iibot.json}。启动失败（配置非法、
 * 世界事实非法、缺 LLM/API key、NapCat/LLM 构造失败）一律 fail-closed，不启动玩家聊天；
 * 错误消息只给可操作提示，不打印密钥。JVM shutdown hook 按固定顺序关闭运行时组件。</p>
 *
 * <p>当配置 {@code control.enabled=true} 时，启动带 Bearer 鉴权的本地 MCP 控制面；
 * 缺省关闭。控制面只绑回环地址，token 只从配置指定的环境变量读取。</p>
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        Path configPath;
        try {
            configPath = parseConfigPath(args);
        } catch (ConfigException e) {
            LOG.error("参数错误: {}", e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        AppRuntime runtime = null;
        try {
            AppConfig config = AppConfigLoader.load(configPath);
            runtime = AppBootstrap.build(config);
            AppRuntime shutdownTarget = runtime;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(shutdownTarget::close, "iibot-shutdown"));
            runtime.start();
            LOG.info("iiBotMosire 已启动。按 Ctrl+C 退出。");
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

    private static Path parseConfigPath(String[] args) {
        if (args == null || args.length == 0) {
            throw new ConfigException("缺少 --config <path>");
        }
        Path configPath = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].isBlank()
                        || args[i + 1].startsWith("--")) {
                    throw new ConfigException("--config 需要一个路径参数");
                }
                if (configPath != null) {
                    throw new ConfigException("--config 只能出现一次");
                }
                configPath = Path.of(args[++i]);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                System.exit(0);
            } else {
                throw new ConfigException("未知参数: " + arg);
            }
        }
        if (configPath == null) {
            throw new ConfigException("缺少 --config <path>");
        }
        return configPath;
    }

    private static void printUsage() {
        System.err.println("用法: java -jar iibot-app.jar --config <path-to-config.json>");
    }
}
