/**
 * 交互-推演 Agent 运行时。
 *
 * <p>本包是 app 组合根与纯领域内核之间的适配层：秘书/GM 使用 AgentLib 的
 * {@code AgentLoop}、{@code ContextWindow} 与 {@code ToolCallAuthorizer}，但所有身份、可见性、
 * 回合版本和世界提交仍由宿主绑定与 {@code TaskflowEngine} 校验。工具参数不接受 playerId
 * 等身份字段；秘书 prompt 只含公共事实与该玩家 brief；本包不发送 QQ 消息，也不启动后台线程。
 */
package io.mosire.iibot.taskflow.agent;
