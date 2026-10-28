package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.inbox.BotInboxRuntimeOptions;
import io.mosire.iibot.connect.napcat.NapCatAdapter;
import io.mosire.iibot.connect.napcat.NapCatConfig;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Bot runtime 启动器：接收已经加载、校验并应用日志配置的 {@link BotConfig}，避免二次加载。
 *
 * <p>P3 只使用第一个 enabled account；P4 才由 AccountRegistry 启动全部账号。没有真实
 * QQ/Inbox/MCP 网络连接会由测试直接调用本类触发，诊断命令不会进入这里。</p>
 */
public final class BotMain {

    private BotMain() {
    }

    public static int runLoaded(BotConfig config, List<Path> configSources,
                                SecretProvider secrets) {
        String bootId = LogContext.newBootId();
        try (LogContext.Scope ignored = LogContext.openBoot(bootId)) {
            return runWithBootId(config, configSources, secrets, bootId);
        }
    }

    private static int runWithBootId(BotConfig config, List<Path> configSources,
                                     SecretProvider secrets, String bootId) {
        Logger log = LoggerFactory.getLogger(BotMain.class);
        BotRuntime runtime = null;
        BotConfig.Account account = config.activeAccount();
        String alias = config.firstEnabledAlias();
        OpsLog.info("event=process_start pid={} bootId={} javaVersion={} cwd={} mode=bot "
                        + "configSources={} enabledAccounts={}",
                ProcessHandle.current().pid(), bootId, System.getProperty("java.version"),
                Path.of("").toAbsolutePath(), configSources, config.accounts().size());
        log.debug("event=log_config source=json+cli logDir={} rootLevel={} opsLevel={} "
                        + "levels={}", config.logging().directory(),
                config.logging().rootLevel(), config.logging().opsLevel(),
                config.logging().levels());
        try {
            BotConfig.OneBot oneBot = account.oneBot();
            String controlToken = secrets.lookup(config.control().bearerTokenEnv())
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalStateException(
                            "Bot MCP token 未配置：环境变量 "
                                    + config.control().bearerTokenEnv() + " 为空"));
            String oneBotToken = oneBot.accessTokenEnv()
                    .map(env -> secrets.lookup(env)
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> new IllegalStateException(
                                    "OneBot token 未配置：环境变量 " + env + " 为空")))
                    .orElse(null);
            NapCatConfig napcat = napCatConfig(account, oneBotToken);
            NapCatAdapter adapter = new NapCatAdapter(napcat);
            OpsLog.info("event=account_registered alias={} accountId={} adapter={}",
                    alias, account.accountId(), adapter.adapterId());
            log.debug("event=adapter_configured adapter={} alias={} accountId={} "
                            + "wsScheme={} httpScheme={} accessTokenEnvConfigured={} "
                            + "suppressSelfMessages={}",
                    adapter.adapterId(), alias, account.accountId(),
                    oneBot.eventWebSocketUri().getScheme(),
                    oneBot.httpBaseUri().getScheme(),
                    oneBot.accessTokenEnv().isPresent(), oneBot.suppressSelfMessages());
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
            BotConfig.MessageBoard board = config.messageBoard();
            BotRuntimeOptions options = new BotRuntimeOptions(
                    config.dataDir(),
                    account.accountId(),
                    config.control().host(),
                    config.control().port(),
                    config.control().path(),
                    controlToken,
                    inboxOptions,
                    board.summaryDebounceMillis(),
                    board.maxPreviewMessages(),
                    Duration.ofSeconds(board.outbox().sendTimeoutSeconds()),
                    oneBot.privateTempGroupId().orElse(null),
                    Duration.ofMillis(board.outbox().minSendIntervalMillis()),
                    board.weighting().toSettings(board.maxPreviewMessages()));
            OpsLog.info("event=config_loaded sources={} enabledAccounts={} activeAlias={} "
                            + "accountId={} dataDir={} controlHost={} controlPort={} "
                            + "controlPath={} inboxId={} digestDebounceMs={} "
                            + "maxPreviewMessages={}",
                    configSources.size(), config.accounts().values().stream()
                            .filter(BotConfig.Account::enabled).count(), alias,
                    account.accountId(), config.dataDir(), config.control().host(),
                    config.control().port(), config.control().path(),
                    config.inbox().inboxId(), board.summaryDebounceMillis(),
                    board.maxPreviewMessages());
            runtime = BotRuntime.start(adapter, options);
            BotRuntime shutdownTarget = runtime;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> {
                        try (LogContext.Scope ignored = LogContext.openBoot(bootId)) {
                            OpsLog.info("event=bot_shutdown_requested");
                            shutdownTarget.close();
                        }
                    }, "iibot-bot-shutdown"));
            log.info("iiBot bot-first 已启动。按 Ctrl+C 退出。");
            OpsLog.info("event=bot_runtime_waiting controlPort={} inboxApiPort={} "
                            + "inboxMcpPort={}",
                    runtime.mcpServer().boundPort(), runtime.inboxRuntime().apiPort(),
                    runtime.inboxRuntime().mcpPort());
            new CountDownLatch(1).await();
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (RuntimeException | IOException startupFailure) {
            log.error("bot-first 启动失败: {}", startupFailure.getMessage());
            OpsLog.error("event=bot_process_failed errorType={}",
                    startupFailure.getClass().getSimpleName());
            if (runtime != null) {
                runtime.close();
            }
            return 1;
        }
    }

    private static NapCatConfig napCatConfig(BotConfig.Account account,
                                             String accessToken) {
        BotConfig.OneBot source = account.oneBot();
        NapCatConfig.Builder builder = NapCatConfig.builder(
                        source.eventWebSocketUri(), source.httpBaseUri())
                .accountId(account.accountId())
                .requestTimeout(Duration.ofSeconds(source.requestTimeoutSeconds()))
                .maxTextLength(source.maxTextLength())
                .privateTempGroupId(source.privateTempGroupId().orElse(null))
                .suppressSelfMessages(source.suppressSelfMessages());
        if (accessToken != null) {
            builder.accessToken(accessToken);
        }
        return builder.build();
    }
}
