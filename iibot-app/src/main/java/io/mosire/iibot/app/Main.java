package io.mosire.iibot.app;

import io.mosire.iibot.app.bot.BotConfig;
import io.mosire.iibot.app.bot.BotConfigBootstrap;
import io.mosire.iibot.app.bot.BotConfigLoader;
import io.mosire.iibot.app.bot.BotMain;
import io.mosire.iibot.app.config.ConfigArguments;
import io.mosire.iibot.app.config.ConfigException;
import io.mosire.iibot.app.config.EffectiveConfigPrinter;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.logging.LoggingConfigurer;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * iiBotMosire 唯一可运行入口。
 *
 * <p>参数解析、配置加载与日志属性应用都发生在任何业务 Logger 初始化之前；配置错误只写
 * stderr，不依赖日志系统。--check-config / --print-effective-config 不构造 runtime、
 * 不连接 QQ/Inbox/MCP。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int rc = execute(args, Path.of("config", "bot"),
                SecretProvider.environment(), System.out, System.err);
        if (rc != 0) {
            System.exit(rc);
        }
    }

    /** 可注入默认目录、secret 来源与输出流的测试入口。 */
    public static int execute(String[] args, Path defaultConfigSource,
                              SecretProvider secrets, PrintStream out, PrintStream err) {
        Objects.requireNonNull(defaultConfigSource, "defaultConfigSource");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        ConfigArguments.ParseResult parsed;
        try {
            parsed = ConfigArguments.parse(args, defaultConfigSource);
        } catch (ConfigException failure) {
            err.println(ConfigException.invalid((Path) null, "/", "valid CLI arguments",
                    "invalid argument", failure.getMessage()).getMessage());
            printUsage(err);
            return 2;
        }
        if (parsed.helpRequested()) {
            printUsage(out);
            return 0;
        }

        boolean mayBootstrap = !parsed.explicitConfig() && !parsed.diagnosticRequested();
        if (mayBootstrap) {
            try {
                BotConfigBootstrap.Result bootstrap =
                        BotConfigBootstrap.ensureDefault(defaultConfigSource);
                if (bootstrap.generated()) {
                    printBootstrapNotice(bootstrap, secrets, err);
                    return 2;
                }
            } catch (ConfigException failure) {
                err.println(failure.getMessage());
                return 1;
            }
        }

        BotConfigLoader.LoadResult result;
        try {
            result = BotConfigLoader.loadDetailed(parsed.sources(), secrets);
        } catch (ConfigException failure) {
            err.println(failure.getMessage());
            return 1;
        }

        try {
            LoggingConfigurer.apply(result.config().logging(),
                    parsed.logLevel().orElse(null));
        } catch (RuntimeException failure) {
            err.println(ConfigException.invalid((Path) null, "/logging",
                    "applicable logging configuration", "runtime error",
                    failure.getMessage()).getMessage());
            return 1;
        }

        String logLevelOverride = parsed.logLevel().orElse(null);
        if (parsed.printEffectiveConfig()) {
            out.println(effectivePrinter(secrets, logLevelOverride).json(result.config()));
            printSecretErrors(result, err);
            return result.secretReady() ? 0 : 1;
        }
        if (parsed.checkConfig()) {
            printCheckResult(parsed.sources(), result, secrets, logLevelOverride, out, err);
            return result.secretReady() ? 0 : 1;
        }
        return BotMain.runLoaded(result.config(), parsed.sources(), secrets);
    }

    private static void printCheckResult(List<Path> sources,
                                         BotConfigLoader.LoadResult result,
                                         SecretProvider secrets, String logLevelOverride,
                                         PrintStream out, PrintStream err) {
        out.println(result.secretReady() ? "CONFIG_VALID" : "CONFIG_INVALID");
        out.println("sources:");
        for (int index = 0; index < sources.size(); index++) {
            out.println("  [" + index + "] " + sources.get(index)
                    .toAbsolutePath().normalize());
        }
        out.println("secretEnv:");
        BotConfig config = result.config();
        printSecretStatus(out, secrets, config.control().bearerTokenEnv());
        for (BotConfig.Account account : config.accounts().values()) {
            if (account.oneBot() != null) {
                account.oneBot().accessTokenEnv().ifPresent(
                        env -> printSecretStatus(out, secrets, env));
            }
            if (account.bilibili() != null) {
                printSecretStatus(out, secrets,
                        account.bilibili().credentialFileEnv());
            }
        }
        out.println("effectiveConfig:");
        out.println(effectivePrinter(secrets, logLevelOverride).json(config));
        printSecretErrors(result, err);
    }

    private static EffectiveConfigPrinter effectivePrinter(SecretProvider secrets,
                                                            String logLevelOverride) {
        return new EffectiveConfigPrinter(secrets, logLevelOverride, logLevelOverride);
    }

    private static void printSecretStatus(PrintStream out, SecretProvider secrets, String env) {
        boolean set = secrets.lookup(env).filter(value -> !value.isBlank()).isPresent();
        out.println("  " + env + ": " + (set ? "SET" : "MISSING"));
    }

    private static void printSecretErrors(BotConfigLoader.LoadResult result, PrintStream err) {
        for (ConfigException failure : result.secretErrors()) {
            err.println(failure.getMessage());
        }
    }

    private static void printBootstrapNotice(BotConfigBootstrap.Result result,
                                             SecretProvider secrets, PrintStream err) {
        err.println("============================================================");
        err.println("首次启动：未发现配置，已生成完整模板：");
        for (Path file : result.files()) {
            err.println("  " + file);
        }
        err.println("请至少修改账号字段：");
        err.println("  accounts.main.accountId");
        err.println("  accounts.main.oneBot.eventWebSocketUri / httpBaseUri");
        err.println("  control.bearerTokenEnv / inbox 设置");
        err.println("secret 环境变量状态（只显示 SET/MISSING）：");
        for (String env : List.of("IIBOT_NAPCAT_ACCESS_TOKEN", "IIBOT_CONTROL_TOKEN")) {
            boolean set = secrets.lookup(env).filter(value -> !value.isBlank()).isPresent();
            err.println("  " + env + ": " + (set ? "SET" : "MISSING"));
        }
        err.println("然后运行: java -jar iibot-app-0.1.0-SNAPSHOT.jar --check-config");
        err.println("本次未启动 QQ/Inbox/MCP。rc=2");
        err.println("============================================================");
    }

    private static void printUsage(PrintStream stream) {
        stream.println("用法: java -jar iibot-app.jar [选项]");
        stream.println("  --config <file-or-dir>          可重复；目录内 *.json 按文件名排序深合并");
        stream.println("  --check-config                  加载+校验配置，不启动网络；rc=0/1");
        stream.println("  --print-effective-config        输出脱敏有效 JSON；secret 只显示 SET/MISSING");
        stream.println("  --log-level <LEVEL>             TRACE|DEBUG|INFO|WARN|ERROR|OFF，覆盖 root/ops");
        stream.println("  -h, --help                      显示帮助");
        stream.println("省略 --config 时读取 config/bot/；首次没有 JSON 会生成单一 bot.json 后退出 rc=2。");
        stream.println("配置文档: config/README.md；schema: config/schema/bot-config.schema.json");
    }
}
