package io.mosire.iibot.connect.agent;

import io.mosire.iibot.connect.core.MessagingAdapter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 线程安全的适配器注册表。
 *
 * <p>本类只按 {@link MessagingAdapter#adapterId()} 管理已装配适配器。内部映射不会暴露给调用方：
 * {@link #list()} 返回按 id 排序的不可变快照，{@link #find(String)} 返回只读的 {@link Optional}。
 * 注册、查询和移除均可并发调用。</p>
 *
 * <p>适配器 id 同时参与工具资源路径，因此注册时拒绝空白、路径分隔符、控制字符和首尾空白，
 * 避免工具名/资源路径被不可信输入重新解释。</p>
 */
public final class AdapterRegistry {

    private final ConcurrentMap<String, MessagingAdapter> adapters = new ConcurrentHashMap<>();

    /** 创建空注册表。 */
    public AdapterRegistry() {
    }

    /**
     * 使用给定适配器创建注册表。
     *
     * @param initialAdapters 初始适配器集合；null 视为空
     * @throws IllegalArgumentException 任一适配器 id 非法或重复
     */
    public AdapterRegistry(Collection<? extends MessagingAdapter> initialAdapters) {
        if (initialAdapters != null) {
            for (MessagingAdapter adapter : initialAdapters) {
                register(adapter);
            }
        }
    }

    /**
     * 注册一个适配器。
     *
     * @param adapter 非 null 适配器
     * @return 被注册的适配器
     * @throws IllegalArgumentException 适配器为 null、id 非法或 id 已存在
     */
    public MessagingAdapter register(MessagingAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String adapterId = requireSafeIdentifier(adapter.adapterId(), "adapterId");
        MessagingAdapter existing = adapters.putIfAbsent(adapterId, adapter);
        if (existing != null) {
            throw new IllegalArgumentException("适配器 id 重复: " + adapterId);
        }
        return adapter;
    }

    /**
     * 以同 id 替换已有适配器；不存在时直接注册。
     *
     * <p>该方法供宿主热替换连接实例使用，不改变工具侧按 id 查找的语义。</p>
     *
     * @param adapter 非 null 适配器
     * @return 被替换的旧适配器；原来不存在时为空
     */
    public Optional<MessagingAdapter> replace(MessagingAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String adapterId = requireSafeIdentifier(adapter.adapterId(), "adapterId");
        return Optional.ofNullable(adapters.put(adapterId, adapter));
    }

    /**
     * 移除指定 id 的适配器。
     *
     * @param adapterId 适配器 id
     * @return 被移除的适配器；不存在或 id 非法时为空
     */
    public Optional<MessagingAdapter> unregister(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.remove(adapterId));
    }

    /**
     * 按 id 查找适配器。
     *
     * @param adapterId 适配器 id
     * @return 不可变可选视图
     */
    public Optional<MessagingAdapter> find(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(adapterId));
    }

    /**
     * 返回当前适配器的不可变快照，按 adapterId 字典序排序。
     *
     * @return 不可变列表；无适配器时为空列表
     */
    public List<MessagingAdapter> list() {
        List<MessagingAdapter> snapshot = new ArrayList<>(adapters.values());
        snapshot.sort(Comparator.comparing(MessagingAdapter::adapterId));
        return List.copyOf(snapshot);
    }

    /**
     * 返回当前 adapterId 的不可变快照，按字典序排序。
     *
     * @return 不可变 id 列表
     */
    public List<String> adapterIds() {
        return adapters.keySet().stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    /** @return 当前适配器数量 */
    public int size() {
        return adapters.size();
    }

    /** @return 当前是否没有适配器 */
    public boolean isEmpty() {
        return adapters.isEmpty();
    }

    /**
     * 校验参与资源路径的标识符。
     *
     * <p>允许常见 id 字符，包括 {@code :}、{@code -}、{@code _} 与 {@code .}；拒绝空白、首尾空白、
     * {@code /}、{@code \}、NUL、控制字符，以及单独的 {@code .}/{@code ..} 路径段。</p>
     */
    static String requireSafeIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(fieldName + " 不能有首尾空白");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(fieldName + " 不能是路径段: " + value);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '\\' || character == '\0'
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException(fieldName + " 含不允许的字符: " + value);
            }
        }
        if (value.length() > 256) {
            throw new IllegalArgumentException(fieldName + " 过长");
        }
        return value;
    }
}
