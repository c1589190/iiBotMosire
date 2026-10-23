package io.mosire.iibot.app.config;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmProtocol;
import io.mosire.agentlib.llm.LlmTransport;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * 从配置与 {@link SecretProvider} 构造真实 OpenAI 兼容客户端。
 *
 * <p>缺 API key、环境变量为空或客户端构造失败都 fail-closed。日志只打印密钥来源与长度，
 * 不打印值；构造异常也不把密钥放进消息。构造本身不发网络请求。</p>
 */
public final class LlmClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LlmClientFactory.class);

    private LlmClientFactory() {
    }

    /** 构造真实 LLM 客户端；失败时抛出不含密钥的可操作异常。 */
    public static LlmClient create(AppConfig.Llm config, SecretProvider secrets) {
        if (config == null) {
            throw new IllegalStateException("LLM 配置缺失：真实启动必须提供 llm 配置块");
        }
        if (secrets == null) {
            throw new IllegalStateException("LLM 密钥来源缺失");
        }
        String envName = config.apiKeyEnv().orElseThrow(() -> new IllegalStateException(
                "LLM 配置缺少 apiKeyEnv：拒绝以无密钥方式启动玩家聊天"));
        String apiKey = secrets.lookup(envName).filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "LLM 密钥未提供：环境变量未设置或为空。请设置 " + envName
                                + " 后重启（不会回退到匿名调用）"));
        LOG.info("LLM 密钥来源=env:{} 长度={}", envName, apiKey.length());

        LlmTransport transport = new LlmTransport(
                LlmProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(config.connectTimeoutSeconds()),
                Duration.ofSeconds(config.readTimeoutSeconds()),
                config.echoReasoningContent());
        ModelRoute route = ModelRoute.of("iibot", config.baseUrl(), config.model(), envName,
                transport);
        try {
            return new OpenAICompatibleLlmClient(route, () -> Optional.of(apiKey));
        } catch (RuntimeException constructionFailure) {
            // 不连带打印 constructionFailure.getMessage()：库的校验消息可能包含完整 baseUrl 等
            // 部署细节；这里只给稳定的操作建议。
            throw new IllegalStateException(
                    "LLM 客户端构造失败：请检查 llm.baseUrl（必须是 API 根）、model 与超时配置");
        }
    }
}
