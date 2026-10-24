package io.mosire.iibot.taskflow.event;

/**
 * 领域事件类型常量。
 *
 * <p>这些字符串是 events.db 中 {@code events.type} 的稳定值。领域服务只使用常量，
 * 查询/投影按类型或前缀过滤，禁止在调用点散落裸字符串。事件类型是存储契约的一部分，
 * 已发布的值不得随意重命名。</p>
 */
public final class DomainEventTypes {

    public static final String POST_PREFIX = "POST_";
    public static final String COMPLAINT_PREFIX = "COMPLAINT_";
    public static final String ACTION_PREFIX = "ACTION_";
    public static final String PACKAGE_PREFIX = "PACKAGE_";
    public static final String BATCH_PREFIX = "BATCH_";
    public static final String PUBLICATION_PREFIX = "PUBLICATION_";
    public static final String TASKFLOW_SNAPSHOT_PREFIX = "TASKFLOW_SNAPSHOT";

    public static final String POST_DRAFTED = "POST_DRAFTED";
    public static final String POST_APPROVED = "POST_APPROVED";
    public static final String POST_REJECTED = "POST_REJECTED";
    public static final String POST_PUBLISHED = "POST_PUBLISHED";
    public static final String POST_DELIVERY_CHANGED = "POST_DELIVERY_CHANGED";
    public static final String POST_LEGACY_MIGRATED = "POST_LEGACY_MIGRATED";
    /**
     * 迁移完成标记：content 是包含 migrationEventType/migrationEventSeq/sourceFile/
     * recordCount/contentHash 的 JSON 元数据对象，不复制领域正文。
     */
    public static final String LEGACY_POST_MIGRATION_RECONCILED =
            "LEGACY_POST_MIGRATION_RECONCILED";
    /** 兼容旧 Store.save(snapshot) 的通用完整快照事件。 */
    public static final String POST_SNAPSHOT = "POST_SNAPSHOT";

    public static final String COMPLAINT_SUBMITTED = "COMPLAINT_SUBMITTED";
    public static final String COMPLAINT_RESPONDED = "COMPLAINT_RESPONDED";
    public static final String COMPLAINT_DELIVERY_CHANGED = "COMPLAINT_DELIVERY_CHANGED";
    public static final String COMPLAINT_LEGACY_MIGRATED = "COMPLAINT_LEGACY_MIGRATED";
    public static final String LEGACY_COMPLAINT_MIGRATION_RECONCILED =
            "LEGACY_COMPLAINT_MIGRATION_RECONCILED";
    /** 兼容旧 Store.save(snapshot) 的通用完整快照事件。 */
    public static final String COMPLAINT_SNAPSHOT = "COMPLAINT_SNAPSHOT";

    public static final String ACTION_SUBMITTED = "ACTION_SUBMITTED";
    public static final String ACTION_WITHDRAWN = "ACTION_WITHDRAWN";
    public static final String ACTION_SELECTED = "ACTION_SELECTED";
    public static final String ACTION_RELEASED = "ACTION_RELEASED";
    public static final String ACTION_COMMITTED = "ACTION_COMMITTED";
    /** 兼容旧 Store.save(snapshot) 的通用完整快照事件。 */
    public static final String ACTION_SNAPSHOT = "ACTION_SNAPSHOT";

    public static final String PACKAGE_PROPOSED = "PACKAGE_PROPOSED";
    public static final String PACKAGE_APPENDED = "PACKAGE_APPENDED";
    public static final String PACKAGE_ITEM_REPLACED = "PACKAGE_ITEM_REPLACED";
    public static final String PACKAGE_ITEM_REMOVED = "PACKAGE_ITEM_REMOVED";
    public static final String PACKAGE_ITEM_DECIDED = "PACKAGE_ITEM_DECIDED";
    public static final String PACKAGE_DECIDED = "PACKAGE_DECIDED";
    public static final String PACKAGE_COMMITTED = "PACKAGE_COMMITTED";
    public static final String PACKAGE_PROJECTION = "PACKAGE_PROJECTION";
    /** P5-A 兼容常量；P5-B 实际写入 STARTED/APPLIED/FAILED 三阶段事件。 */
    public static final String PACKAGE_COMMAND = "PACKAGE_COMMAND";
    public static final String PACKAGE_COMMAND_PREFIX = "PACKAGE_COMMAND_";
    public static final String PACKAGE_COMMAND_STARTED = "PACKAGE_COMMAND_STARTED";
    public static final String PACKAGE_COMMAND_APPLIED = "PACKAGE_COMMAND_APPLIED";
    public static final String PACKAGE_COMMAND_FAILED = "PACKAGE_COMMAND_FAILED";

    public static final String BATCH_CREATED = "BATCH_CREATED";
    public static final String BATCH_COMPLETED = "BATCH_COMPLETED";
    public static final String BATCH_PROJECTION = "BATCH_PROJECTION";

    /** 一次 ActionBatch 的公共/私密发布草稿已完成映射；content 为完整 PublicationRecord。 */
    public static final String PUBLICATION_GENERATED = "PUBLICATION_GENERATED";

    public static final String TASKFLOW_SNAPSHOT = "TASKFLOW_SNAPSHOT";
    public static final String TASKFLOW_SNAPSHOT_MIGRATED = "TASKFLOW_SNAPSHOT_MIGRATED";
    /** Taskflow 迁移/首次基线完成标记；不得匹配 TASKFLOW_SNAPSHOT 前缀查询。 */
    public static final String TASKFLOW_BASELINE_RECONCILED = "TASKFLOW_BASELINE_RECONCILED";

    public static final String CONTROL_INITIALIZED = "CONTROL_INITIALIZED";
    /** 控制面完整状态快照；content 为 ControlStateSnapshot，summary 只放索引/计数。 */
    public static final String CONTROL_STATE = "CONTROL_STATE";
    public static final String CONTROL_PUBLIC_GROUP_SET = "CONTROL_PUBLIC_GROUP_SET";
    public static final String CONTROL_PLAYER_REGISTERED = "CONTROL_PLAYER_REGISTERED";
    public static final String CONTROL_DIRECTOR_REGISTERED = "CONTROL_DIRECTOR_REGISTERED";
    public static final String CONTROL_SECRETARY_BOUND = "CONTROL_SECRETARY_BOUND";
    public static final String CONTROL_WORLD_LOADED = "CONTROL_WORLD_LOADED";
    public static final String CONTROL_GM_DOC_LOADED = "CONTROL_GM_DOC_LOADED";
    // 旧设计文档中的控制事件名保留为兼容别名。
    public static final String PUBLIC_GROUP_SET = "PUBLIC_GROUP_SET";
    public static final String PLAYER_BOUND = "PLAYER_BOUND";
    public static final String DIRECTOR_BOUND = "DIRECTOR_BOUND";

    public static final String GM_DOC_CREATED = "GM_DOC_CREATED";
    public static final String GM_DOC_UPDATED = "GM_DOC_UPDATED";
    public static final String GM_DOC_DELETED = "GM_DOC_DELETED";

    public static final String SECRETARY_MESSAGE = "SECRETARY_MESSAGE";
    public static final String GM_MESSAGE = "GM_MESSAGE";

    public static final String TICKET_PREFIX = "TICKET_";
    public static final String TICKET_CREATED = "TICKET_CREATED";
    public static final String TICKET_CLAIMED = "TICKET_CLAIMED";
    public static final String TICKET_CONFIRMED = "TICKET_CONFIRMED";
    public static final String TICKET_REPLIED = "TICKET_REPLIED";
    public static final String TICKET_BATCH_REPLIED = "TICKET_BATCH_REPLIED";
    public static final String TICKET_ACTION_LINKED = "TICKET_ACTION_LINKED";
    public static final String TICKET_RESOLVED = "TICKET_RESOLVED";
    public static final String TICKET_CLOSED = "TICKET_CLOSED";
    /** 兼容 Store.save(snapshot) 的完整 TicketSnapshot 事件。 */
    public static final String TICKET_SNAPSHOT = "TICKET_SNAPSHOT";
    public static final String TURN_OPENED = "TURN_OPENED";
    public static final String TURN_LOCKED = "TURN_LOCKED";
    public static final String TURN_ADJUDICATED = "TURN_ADJUDICATED";
    public static final String TURN_PUBLISHED = "TURN_PUBLISHED";

    private DomainEventTypes() {
    }
}
