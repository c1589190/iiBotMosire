package io.mosire.iibot.taskflow.decision;

import java.util.Map;
import java.util.Objects;

/**
 * 包条目只读视图。默认 {@code args == null}（摘要面）；只有显式 full=true 才携带明文 args。
 * 即使携带明文，{@link #toString()} 也不输出 args。
 */
public record PackageItemView(
        int index,
        String toolName,
        String argsDigest,
        String summary,
        Map<String, Object> args) {

    public PackageItemView {
        if (index < 0) {
            throw new IllegalArgumentException("index 不能为负");
        }
        Objects.requireNonNull(toolName, "toolName");
        argsDigest = argsDigest == null ? "" : argsDigest;
        summary = summary == null ? "" : summary;
        args = args == null ? null : Map.copyOf(args);
    }

    public boolean includesArgs() {
        return args != null;
    }

    @Override
    public String toString() {
        return "PackageItemView[index=" + index
                + ", toolName=" + toolName
                + ", argsDigest=" + argsDigest
                + ", summary=" + summary
                + ", includesArgs=" + includesArgs()
                + ']';
    }
}
