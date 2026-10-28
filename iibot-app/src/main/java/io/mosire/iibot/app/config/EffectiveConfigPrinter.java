package io.mosire.iibot.app.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.bot.BotConfig;

import java.util.Map;
import java.util.Objects;

/**
 * 生成脱敏后的有效配置 JSON。
 *
 * <p>配置本来只保存 secret 环境变量名；本类仍再次保证任何 token/secret/password/
 * authorization/credential 字段只输出 {@code <env:NAME:SET|MISSING>}，绝不读取或输出
 * secret 值。返回的 JSON 可直接给 --print-effective-config。</p>
 */
public final class EffectiveConfigPrinter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SecretProvider secrets;
    private final String rootLevelOverride;
    private final String opsLevelOverride;

    public EffectiveConfigPrinter(SecretProvider secrets) {
        this(secrets, null, null);
    }

    public EffectiveConfigPrinter(SecretProvider secrets, String rootLevelOverride,
                                  String opsLevelOverride) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.rootLevelOverride = rootLevelOverride;
        this.opsLevelOverride = opsLevelOverride;
    }

    public ObjectNode tree(BotConfig config) {
        ObjectNode root = JSON.createObjectNode();
        root.put("dataDir", config.dataDir().toString());

        ObjectNode accounts = root.putObject("accounts");
        for (Map.Entry<String, BotConfig.Account> entry : config.accounts().entrySet()) {
            BotConfig.Account account = entry.getValue();
            ObjectNode node = accounts.putObject(entry.getKey());
            node.put("enabled", account.enabled());
            node.put("adapter", account.adapter());
            node.put("accountId", account.accountId());
            ObjectNode oneBot = node.putObject("oneBot");
            oneBot.put("eventWebSocketUri", account.oneBot().eventWebSocketUri().toString());
            oneBot.put("httpBaseUri", account.oneBot().httpBaseUri().toString());
            oneBot.put("accessTokenEnv", account.oneBot().accessTokenEnv()
                    .map(this::secretMarker).orElse(""));
            oneBot.put("privateTempGroupId",
                    account.oneBot().privateTempGroupId().orElse(""));
            oneBot.put("suppressSelfMessages", account.oneBot().suppressSelfMessages());
            oneBot.put("requestTimeoutSeconds", account.oneBot().requestTimeoutSeconds());
            oneBot.put("maxTextLength", account.oneBot().maxTextLength());
        }

        BotConfig.Control control = config.control();
        ObjectNode controlNode = root.putObject("control");
        controlNode.put("host", control.host());
        controlNode.put("port", control.port());
        controlNode.put("path", control.path());
        controlNode.put("bearerTokenEnv", secretMarker(control.bearerTokenEnv()));

        BotConfig.Inbox inbox = config.inbox();
        ObjectNode inboxNode = root.putObject("inbox");
        inboxNode.put("dataDir", inbox.dataDir().toString());
        inboxNode.put("inboxId", inbox.inboxId());
        inboxNode.put("bindingLabel", inbox.bindingLabel());
        ArrayNode channels = inboxNode.putArray("channels");
        inbox.channels().forEach(channels::add);
        inboxNode.put("bindingTtlHours", inbox.bindingTtlHours());
        inboxNode.put("apiHost", inbox.apiHost());
        inboxNode.put("apiPort", inbox.apiPort());
        inboxNode.put("mcpHost", inbox.mcpHost());
        inboxNode.put("mcpPort", inbox.mcpPort());
        inboxNode.put("mcpPath", inbox.mcpPath());
        inboxNode.put("heartbeatMillis", inbox.heartbeatMillis());
        inboxNode.put("claimSweepIntervalMillis", inbox.claimSweepIntervalMillis());

        BotConfig.MessageBoard board = config.messageBoard();
        ObjectNode boardNode = root.putObject("messageBoard");
        boardNode.put("summaryDebounceMillis", board.summaryDebounceMillis());
        boardNode.put("maxPreviewMessages", board.maxPreviewMessages());
        ObjectNode outbox = boardNode.putObject("outbox");
        outbox.put("sendTimeoutSeconds", board.outbox().sendTimeoutSeconds());
        outbox.put("minSendIntervalMillis", board.outbox().minSendIntervalMillis());
        BotConfig.Weighting weighting = board.weighting();
        ObjectNode weightingNode = boardNode.putObject("weighting");
        weightingNode.put("defaultPrivateSourceWeight",
                weighting.defaultPrivateSourceWeight());
        weightingNode.put("defaultGroupSourceWeight",
                weighting.defaultGroupSourceWeight());
        weightingNode.put("defaultNotifyThreshold", weighting.defaultNotifyThreshold());
        ObjectNode signals = weightingNode.putObject("signalWeights");
        weighting.signalWeights().forEach(signals::put);
        ObjectNode keywords = weightingNode.putObject("keywordWeights");
        weighting.keywordWeights().forEach(keywords::put);
        ArrayNode aliases = weightingNode.putArray("aliases");
        weighting.aliases().forEach(aliases::add);
        ArrayNode commands = weightingNode.putArray("commandPrefixes");
        weighting.commandPrefixes().forEach(commands::add);
        weightingNode.put("quietMillis", weighting.quietMillis());
        weightingNode.put("cooldownMillis", weighting.cooldownMillis());

        BotConfig.Logging logging = config.logging();
        ObjectNode loggingNode = root.putObject("logging");
        loggingNode.put("directory", logging.directory().toString());
        loggingNode.put("console", logging.console());
        loggingNode.put("file", logging.file());
        loggingNode.put("rootLevel",
                rootLevelOverride == null ? logging.rootLevel() : rootLevelOverride);
        loggingNode.put("opsLevel",
                opsLevelOverride == null ? logging.opsLevel() : opsLevelOverride);
        ObjectNode levels = loggingNode.putObject("levels");
        logging.levels().forEach(levels::put);
        loggingNode.put("maxFileSize", logging.maxFileSize());
        loggingNode.put("maxHistory", logging.maxHistory());
        return root;
    }

    public String json(BotConfig config) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree(config));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("无法序列化脱敏有效配置", impossible);
        }
    }

    private String secretMarker(String envName) {
        String status = secrets.lookup(envName)
                .filter(value -> !value.isBlank()).isPresent() ? "SET" : "MISSING";
        return "<env:" + envName + ":" + status + ">";
    }
}
