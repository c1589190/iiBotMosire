package io.mosire.iibot.app.bot;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginPollTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginStartTool;
import io.mosire.iibot.app.bilibili.BiliVideoReadTool;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.inbox.BotInboxRuntimeOptions;
import io.mosire.iibot.connect.video.bilibili.BiliLoginService;
import io.mosire.iibot.connect.video.bilibili.BilibiliAdapter;
import io.mosire.iibot.connect.video.bilibili.BilibiliAdapterConfig;
import io.mosire.iibot.connect.video.core.VideoAccountRef;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.napcat.NapCatAdapter;
import io.mosire.iibot.connect.napcat.NapCatConfig;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Bot runtime 启动器：接收已经加载、校验并应用日志配置的 {@link BotConfig}，避免二次加载。
 *
 * <p>P4 遍历所有 enabled account，分别创建 NapCatAdapter 并注册到同一个
 * {@link AccountRegistry}；任一账号配置/凭据/启动失败都回滚并关闭已启动资源。</p>
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
        AccountRegistry accountRegistry = new AccountRegistry();
        List<Map.Entry<String, BotConfig.Account>> enabledAccounts = config.accounts()
                .entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .toList();
        BotConfig.Account activeAccount = enabledAccounts.getFirst().getValue();
        String activeAlias = enabledAccounts.getFirst().getKey();
        OpsLog.info("event=process_start pid={} bootId={} javaVersion={} cwd={} mode=bot "
                        + "configSources={} enabledAccounts={}",
                ProcessHandle.current().pid(), bootId, System.getProperty("java.version"),
                Path.of("").toAbsolutePath(), configSources, enabledAccounts.size());
        log.debug("event=log_config source=json+cli logDir={} rootLevel={} opsLevel={} "
                        + "levels={}", config.logging().directory(),
                config.logging().rootLevel(), config.logging().opsLevel(),
                config.logging().levels());
        try {
            String controlToken = secrets.lookup(config.control().bearerTokenEnv())
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalStateException(
                            "Bot MCP token 未配置：环境变量 "
                                    + config.control().bearerTokenEnv() + " 为空"));

            List<BotRuntimeOptions.AccountOptions> accountOptions = new ArrayList<>();
            List<VideoAccountRef> biliVideoAccounts = new ArrayList<>();
            for (Map.Entry<String, BotConfig.Account> entry : enabledAccounts) {
                String alias = entry.getKey();
                BotConfig.Account account = entry.getValue();
                MessagingAdapter adapter;
                String privateTempGroupId = null;
                if (account.oneBot() != null) {
                    BotConfig.OneBot oneBot = account.oneBot();
                    String oneBotToken = oneBot.accessTokenEnv()
                            .map(env -> secrets.lookup(env)
                                    .filter(value -> !value.isBlank())
                                    .orElseThrow(() -> new IllegalStateException(
                                            "OneBot token 未配置：账号 " + alias
                                                    + " 的环境变量 " + env + " 为空")))
                            .orElse(null);
                    adapter = new NapCatAdapter(napCatConfig(account, oneBotToken));
                    privateTempGroupId = oneBot.privateTempGroupId().orElse(null);
                    log.debug("event=adapter_configured adapter={} alias={} accountId={} "
                                    + "wsScheme={} httpScheme={} accessTokenEnvConfigured={} "
                                    + "suppressSelfMessages={}",
                            adapter.adapterId(), alias, account.accountId(),
                            oneBot.eventWebSocketUri().getScheme(),
                            oneBot.httpBaseUri().getScheme(),
                            oneBot.accessTokenEnv().isPresent(),
                            oneBot.suppressSelfMessages());
                } else {
                    BotConfig.Bilibili bilibili = account.bilibili();
                    String credentialPath = secrets.lookup(bilibili.credentialFileEnv())
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> new IllegalStateException(
                                    "B站凭据文件未配置：账号 " + alias
                                            + " 的环境变量 "
                                            + bilibili.credentialFileEnv() + " 为空"));
                    BilibiliAdapterConfig adapterConfig = new BilibiliAdapterConfig(
                            account.accountId(),
                            Path.of(credentialPath),
                            Duration.ofSeconds(bilibili.pollIntervalSeconds()),
                            bilibili.receivePrivateMessages(),
                            bilibili.receiveCommentReplies(),
                            bilibili.writeEnabled(),
                            bilibili.maxWritesPerDay());
                    adapter = new BilibiliAdapter(adapterConfig);
                    biliVideoAccounts.add(new VideoAccountRef(
                            account.accountId(), Path.of(credentialPath)));
                    log.debug("event=adapter_configured adapter={} alias={} accountId={} "
                                    + "pollSeconds={} privateMessages={} "
                                    + "commentReplies={} writeEnabled={}",
                            adapter.adapterId(), alias, account.accountId(),
                            bilibili.pollIntervalSeconds(),
                            bilibili.receivePrivateMessages(),
                            bilibili.receiveCommentReplies(), bilibili.writeEnabled());
                }
                accountRegistry.register(account.accountId(), adapter);
                accountOptions.add(new BotRuntimeOptions.AccountOptions(
                        alias, account.accountId(), privateTempGroupId));
                OpsLog.info("event=account_registered alias={} accountId={} adapter={}",
                        alias, account.accountId(), adapter.adapterId());
            }

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
                    activeAccount.accountId(),
                    config.control().host(),
                    config.control().port(),
                    config.control().path(),
                    controlToken,
                    inboxOptions,
                    board.summaryDebounceMillis(),
                    board.maxPreviewMessages(),
                    board.inlineMaxChars(),
                    Duration.ofSeconds(board.outbox().sendTimeoutSeconds()),
                    activeAccount.oneBot() == null ? null
                            : activeAccount.oneBot().privateTempGroupId().orElse(null),
                    Duration.ofMillis(board.outbox().minSendIntervalMillis()),
                    board.weighting().toSettings(board.maxPreviewMessages()),
                    List.copyOf(accountOptions));
            OpsLog.info("event=config_loaded sources={} enabledAccounts={} activeAlias={} "
                            + "accountId={} dataDir={} controlHost={} controlPort={} "
                            + "controlPath={} inboxId={} digestDebounceMs={} "
                            + "maxPreviewMessages={} inlineMaxChars={}",
                    configSources.size(), enabledAccounts.size(), activeAlias,
                    activeAccount.accountId(), config.dataDir(), config.control().host(),
                    config.control().port(), config.control().path(),
                    config.inbox().inboxId(), board.summaryDebounceMillis(),
                    board.maxPreviewMessages(), board.inlineMaxChars());
            List<AgentTool> extraTools = new ArrayList<>();
            extraTools.add(new BiliVideoReadTool(biliVideoAccounts));
            if (!biliVideoAccounts.isEmpty()) {
                BiliLoginService loginService = new BiliLoginService(biliVideoAccounts);
                extraTools.add(new BiliAccountLoginStartTool(loginService));
                extraTools.add(new BiliAccountLoginPollTool(loginService));
            }
            runtime = BotRuntime.start(accountRegistry, options, extraTools,
                    java.time.Clock.systemUTC());
            BotRuntime shutdownTarget = runtime;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> {
                        try (LogContext.Scope ignored = LogContext.openBoot(bootId)) {
                            OpsLog.info("event=bot_shutdown_requested");
                            shutdownTarget.close();
                        }
                    }, "iibot-bot-shutdown"));
            log.info("iiBot bot-first 已启动，enabled account 数: {}。按 Ctrl+C 退出。",
                    enabledAccounts.size());
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
            } else {
                closeAdapters(accountRegistry);
            }
            return 1;
        }
    }

    private static void closeAdapters(AccountRegistry accountRegistry) {
        for (AccountRegistry.RegisteredAccount registered : accountRegistry.snapshot()) {
            try {
                registered.adapter().close();
            } catch (RuntimeException failure) {
                OpsLog.warn("event=component_close_failed component={} errorType={}",
                        registered.adapter().getClass().getSimpleName(),
                        failure.getClass().getSimpleName());
            }
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
