package io.mosire.iibot.app.control;

import io.mosire.iibot.taskflow.CommandResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 控制面操作结果：稳定 code、脱敏 message、结构化 data 与可选领域回执。
 *
 * <p>该类型同时实现只读 {@link Map} 视图，便于调用方以通用摘要读取；MCP 工具只使用
 * {@link #data()} 构造固定的 {@code ok/code/tool/data} envelope，绝不把物理路径或正文
 * 塞进 data。</p>
 */
public final class ControlOperationResult implements Map<String, Object> {

    private final boolean ok;
    private final String code;
    private final String message;
    private final Map<String, Object> data;
    private final CommandResult commandResult;
    private final Map<String, Object> view;

    public ControlOperationResult(boolean ok, String code, String message,
                                  Map<String, Object> data, CommandResult commandResult) {
        this.ok = ok;
        this.code = code == null || code.isBlank() ? (ok ? "OK" : "INTERNAL_ERROR") : code;
        this.message = message == null ? "" : message;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(
                new LinkedHashMap<>(data));
        this.commandResult = commandResult;
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattened.put("ok", this.ok);
        flattened.put("code", this.code);
        flattened.put("message", this.message);
        flattened.put("data", this.data);
        this.data.forEach(flattened::putIfAbsent);
        this.view = Collections.unmodifiableMap(flattened);
    }

    public static ControlOperationResult success(Map<String, Object> data) {
        return new ControlOperationResult(true, "OK", "", data, null);
    }

    public static ControlOperationResult success(Map<String, Object> data,
                                                 CommandResult commandResult) {
        return new ControlOperationResult(true, "OK", "", data, commandResult);
    }

    public static ControlOperationResult failure(String code, String message) {
        return new ControlOperationResult(false, code, message, Map.of(), null);
    }

    public static ControlOperationResult fromCommand(CommandResult result,
                                                     Map<String, Object> extraData) {
        Objects.requireNonNull(result, "result");
        Map<String, Object> data = new LinkedHashMap<>();
        if (extraData != null) {
            data.putAll(extraData);
        }
        data.put("domainCode", result.code().name());
        data.put("revision", result.revision());
        data.put("engineRevision", result.engineRevision());
        data.put("eventSeq", result.eventSeq());
        result.referenceId().ifPresent(id -> data.put("referenceId", id));
        if (!result.details().isEmpty()) {
            data.put("details", result.details());
        }
        if (result.ok()) {
            return new ControlOperationResult(true, "OK", result.message(), data, result);
        }
        return new ControlOperationResult(false, result.code().name(), result.message(), data, result);
    }

    public boolean ok() {
        return ok;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public Map<String, Object> data() {
        return data;
    }

    public Optional<CommandResult> commandResult() {
        return Optional.ofNullable(commandResult);
    }

    /** 稳定摘要视图；data 同时嵌套保留，不丢失字段。 */
    public Map<String, Object> asMap() {
        return view;
    }

    @Override
    public int size() {
        return view.size();
    }

    @Override
    public boolean isEmpty() {
        return view.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return view.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return view.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return view.get(key);
    }

    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        return view.getOrDefault(key, defaultValue);
    }

    @Override
    public java.util.Set<String> keySet() {
        return view.keySet();
    }

    @Override
    public java.util.Collection<Object> values() {
        return view.values();
    }

    @Override
    public java.util.Set<Map.Entry<String, Object>> entrySet() {
        return view.entrySet();
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("控制结果只读");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("控制结果只读");
    }

    @Override
    public void putAll(Map<? extends String, ?> map) {
        throw new UnsupportedOperationException("控制结果只读");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("控制结果只读");
    }

    @Override
    public String toString() {
        return "ControlOperationResult{ok=" + ok + ", code=" + code
                + ", dataKeys=" + data.keySet() + '}';
    }
}
