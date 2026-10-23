package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一组不可变的 QQ 连接工具。
 *
 * <p>本类只负责工具集合与注册辅助，<b>不提供</b>直接执行入口。任何实际调用都必须由宿主通过
 * {@code ToolCallAuthorizer}（工具名权限、资源判定与审批闸的唯一入口）完成。</p>
 */
public final class ConnectToolSet implements Iterable<AgentTool> {

    private final List<AgentTool> tools;
    private final Map<String, AgentTool> byName;

    ConnectToolSet(List<AgentTool> tools) {
        Objects.requireNonNull(tools, "tools");
        if (tools.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("工具集合不能包含 null");
        }
        this.tools = List.copyOf(tools);
        Map<String, AgentTool> index = new LinkedHashMap<>();
        for (AgentTool tool : this.tools) {
            AgentTool existing = index.putIfAbsent(tool.name(), tool);
            if (existing != null) {
                throw new IllegalArgumentException("工具名重复: " + tool.name());
            }
        }
        this.byName = Map.copyOf(index);
    }

    /**
     * 返回按工厂定义顺序排列的不可变工具快照。
     *
     * @return 不可变工具列表
     */
    public List<AgentTool> tools() {
        return tools;
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名
     * @return 不可变可选视图
     */
    public Optional<AgentTool> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    /** @return 不可变工具名列表 */
    public List<String> names() {
        return tools.stream().map(AgentTool::name).toList();
    }

    /** @return 工具数量 */
    public int size() {
        return tools.size();
    }

    /** @return 是否为空工具集 */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 原子注册到给定 ToolRegistry。
     *
     * <p>注册本身只是把工具暴露给宿主目录，不是执行；真正调用仍必须经过 {@code ToolCallAuthorizer}。</p>
     *
     * @param registry 宿主工具注册表
     * @return 传入的注册表，便于链式装配
     */
    public ToolRegistry registerInto(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.registerAll(tools);
        return registry;
    }

    /**
     * {@link #registerInto(ToolRegistry)} 的别名，语义相同。
     *
     * @param registry 宿主工具注册表
     * @return 传入的注册表
     */
    public ToolRegistry registerAll(ToolRegistry registry) {
        return registerInto(registry);
    }

    /**
     * {@link #registerInto(ToolRegistry)} 的短别名，语义相同。
     *
     * @param registry 宿主工具注册表
     * @return 传入的注册表
     */
    public ToolRegistry register(ToolRegistry registry) {
        return registerInto(registry);
    }

    @Override
    public Iterator<AgentTool> iterator() {
        return tools.iterator();
    }
}
