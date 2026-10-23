package io.mosire.iibot.taskflow.agent;

import java.util.Objects;

/**
 * system prompt 组装器。
 *
 * <p>{@link AgentRunner} 每次组请求时都会调用它，并把返回的文本放在请求头部、窗口历史之前。
 * 组装器只在当次请求生效，不会写进 {@code ContextWindow}；因此秘书知识更新后下一次请求即可
 * 使用新 prompt，同时历史仍保持原样。</p>
 *
 * <p>实现方必须自己保证“进入 prompt 的内容已经过领域可见性过滤”。秘书 prompt 只能基于
 * {@code SecretaryKnowledge}，不能把完整世界注入。</p>
 */
@FunctionalInterface
public interface SystemPromptAssembler {

    /** @return 非空白 system prompt */
    String assemble();

    /** 固定文本的便捷工厂。 */
    static SystemPromptAssembler of(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("system prompt 不能为空白");
        }
        return () -> text;
    }
}
