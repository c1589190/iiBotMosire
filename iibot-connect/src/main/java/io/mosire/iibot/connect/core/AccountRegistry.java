package io.mosire.iibot.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按稳定业务 accountId 管理多个 {@link MessagingAdapter} 的线程安全注册表。
 *
 * <p>该类只依赖 connect core 的协议中立类型，不依赖 AgentLib、Bot 消息板或配置系统。
 * accountId 是消息板和 thread 的账号分区键；adapterId 只表示具体适配器实例。为避免同一
 * 物理连接被重复注册到多个账号，本类同时保证 accountId 与 adapterId 全局唯一。</p>
 *
 * <p>注册、替换、移除和查询均线程安全。{@link #snapshot()} 返回按 accountId 排序的不可变
 * 快照，调用方无法通过快照修改注册表。</p>
 */
public final class AccountRegistry {

    private final ConcurrentMap<String, MessagingAdapter> adaptersByAccount =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> accountByAdapterId = new ConcurrentHashMap<>();

    /** 创建空注册表。 */
    public AccountRegistry() {
    }

    /**
     * 注册一个账号适配器。
     *
     * @param accountId 非空白稳定账号 ID
     * @param adapter   非 null 适配器
     * @return 已注册适配器
     * @throws IllegalArgumentException accountId/adapterId 为空白、accountId 重复或 adapterId
     *                                  已属于另一个账号
     */
    public synchronized MessagingAdapter register(String accountId, MessagingAdapter adapter) {
        String normalizedAccountId = requireAccountId(accountId);
        MessagingAdapter registered = Objects.requireNonNull(adapter, "adapter");
        String adapterId = requireAdapterId(registered.adapterId());
        if (adaptersByAccount.containsKey(normalizedAccountId)) {
            throw new IllegalArgumentException("accountId 重复: " + normalizedAccountId);
        }
        String owner = accountByAdapterId.get(adapterId);
        if (owner != null) {
            throw new IllegalArgumentException("adapterId 重复: " + adapterId
                    + "，已注册到 accountId=" + owner);
        }
        adaptersByAccount.put(normalizedAccountId, registered);
        accountByAdapterId.put(adapterId, normalizedAccountId);
        return registered;
    }

    /**
     * 替换指定 accountId 的适配器；不存在时直接注册。
     *
     * <p>替换后旧适配器不再由本注册表持有，调用方负责其生命周期。若新 adapterId 已属于
     * 另一个账号则拒绝，避免一个适配器实例被两个账号共享。</p>
     *
     * @param accountId 稳定账号 ID
     * @param adapter   新适配器
     * @return 被替换的旧适配器；此前不存在时为空
     */
    public synchronized Optional<MessagingAdapter> replace(String accountId,
                                                           MessagingAdapter adapter) {
        String normalizedAccountId = requireAccountId(accountId);
        MessagingAdapter replacement = Objects.requireNonNull(adapter, "adapter");
        String replacementAdapterId = requireAdapterId(replacement.adapterId());
        String owner = accountByAdapterId.get(replacementAdapterId);
        if (owner != null && !owner.equals(normalizedAccountId)) {
            throw new IllegalArgumentException("adapterId 重复: " + replacementAdapterId
                    + "，已注册到 accountId=" + owner);
        }
        MessagingAdapter previous = adaptersByAccount.put(normalizedAccountId, replacement);
        if (previous != null && !previous.adapterId().equals(replacementAdapterId)) {
            accountByAdapterId.remove(previous.adapterId(), normalizedAccountId);
        }
        accountByAdapterId.put(replacementAdapterId, normalizedAccountId);
        return Optional.ofNullable(previous);
    }

    /**
     * 移除指定 accountId 的适配器。
     *
     * @param accountId 稳定账号 ID
     * @return 被移除的适配器；不存在或 accountId 空白时为空
     */
    public synchronized Optional<MessagingAdapter> unregister(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        String normalizedAccountId = accountId.trim();
        MessagingAdapter removed = adaptersByAccount.remove(normalizedAccountId);
        if (removed != null) {
            accountByAdapterId.remove(removed.adapterId(), normalizedAccountId);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 按稳定 accountId 查找适配器。
     *
     * @param accountId 稳定账号 ID
     * @return 不可变可选视图；不存在或空白时为空
     */
    public Optional<MessagingAdapter> find(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adaptersByAccount.get(accountId.trim()));
    }

    /**
     * 返回当前注册项的不可变快照，按 accountId 字典序排序。
     *
     * @return 不可变快照；空注册表返回空列表
     */
    public synchronized List<RegisteredAccount> snapshot() {
        List<RegisteredAccount> snapshot = new ArrayList<>(adaptersByAccount.size());
        adaptersByAccount.forEach((accountId, adapter) ->
                snapshot.add(new RegisteredAccount(accountId, adapter)));
        snapshot.sort(Comparator.comparing(RegisteredAccount::accountId));
        return List.copyOf(snapshot);
    }

    /**
     * 返回当前账号 ID 的不可变快照，按字典序排序。
     *
     * @return 不可变 accountId 集合快照
     */
    public Set<String> accountIds() {
        return Collections.unmodifiableSet(new TreeSet<>(adaptersByAccount.keySet()));
    }

    /** @return 当前注册的适配器数量 */
    public int size() {
        return adaptersByAccount.size();
    }

    /** @return 当前是否没有注册任何适配器 */
    public boolean isEmpty() {
        return adaptersByAccount.isEmpty();
    }

    /** 一条不可变的账号注册项。 */
    public record RegisteredAccount(String accountId, MessagingAdapter adapter) {
        public RegisteredAccount {
            accountId = requireAccountId(accountId);
            adapter = Objects.requireNonNull(adapter, "adapter");
        }
    }

    private static String requireAccountId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空白");
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("accountId 不能有首尾空白");
        }
        return normalized;
    }

    private static String requireAdapterId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("adapterId 不能为空白");
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("adapterId 不能有首尾空白");
        }
        return normalized;
    }
}
