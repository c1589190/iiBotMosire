package io.mosire.iibot.taskflow;

/**
 * 交互-推演域中的身份角色。
 *
 * <p>身份必须由宿主配置注入，不能从聊天文本或结构化提案中提升。领域层只保存
 * “已经由宿主认定的角色”，并把授权判断集中在这里和引擎的写操作入口，避免
 * LLM 或接入协议成为权限来源。</p>
 */
public enum ActorRole {
    PLAYER,
    DIRECTOR,
    GM,
    SECRETARY,
    SUPERVISOR
}
