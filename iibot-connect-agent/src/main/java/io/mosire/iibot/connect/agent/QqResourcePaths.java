package io.mosire.iibot.connect.agent;

import io.mosire.iibot.connect.core.OutboundMessage.TargetType;
import io.mosire.agentlib.permission.ResourceId;

/**
 * QQ 工具的资源路径约定。
 *
 * <p>命名空间固定为 {@value #NAMESPACE}。路径冻结如下：</p>
 *
 * <ul>
 *   <li>{@code qq:adapters} — 列出适配器；</li>
 *   <li>{@code qq:<adapterId>} — 查询单个适配器状态或 Bot 账号公开信息；</li>
 *   <li>{@code qq:<adapterId>/<PRIVATE|GROUP>:<targetId>} — 向目标发送消息。</li>
 * </ul>
 *
 * <p>路径只由宿主配置的可达面判定；工具不自行决定是否授权，只调用注入的
 * {@link io.mosire.agentlib.permission.ResourceAuthorizer#require}。</p>
 */
final class QqResourcePaths {

    static final String NAMESPACE = "qq";
    static final String ADAPTERS = "adapters";

    private QqResourcePaths() {
    }

    /** 列出适配器所需的只读资源。 */
    static ResourceId adapters() {
        return ResourceId.of(NAMESPACE, ADAPTERS);
    }

    /** 查询单个适配器状态所需的只读资源。 */
    static ResourceId adapter(String adapterId) {
        return ResourceId.of(NAMESPACE, adapterId);
    }

    /** 向目标发送消息所需的写资源。 */
    static ResourceId target(String adapterId, TargetType targetType, String targetId) {
        return ResourceId.of(NAMESPACE, adapterId + "/" + targetType.name() + ":" + targetId);
    }
}
