package io.mosire.iibot.taskflow.decision;

import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.PackageState;

import java.util.List;
import java.util.Objects;

/**
 * 裁定包只读视图。摘要视图不携带任何条目 args；full=true 时才由宿主填入明文参数并负责
 * 审计读取。{@link #toString()} 永远不打印 args。</p>
 */
public record PackageView(
        String packageId,
        String title,
        String proposerKey,
        PackageState state,
        long createdAtEpochMs,
        long deadlineEpochMs,
        List<PackageItemView> items,
        Decision decision) {

    public PackageView {
        if (packageId == null || packageId.isBlank()) {
            throw new IllegalArgumentException("packageId 不能为空白");
        }
        title = title == null ? "" : title;
        Objects.requireNonNull(proposerKey, "proposerKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
    }

    public boolean full() {
        return items.stream().anyMatch(PackageItemView::includesArgs);
    }

    @Override
    public String toString() {
        return "PackageView[packageId=" + packageId
                + ", title=" + title
                + ", proposerKey=" + proposerKey
                + ", state=" + state
                + ", itemCount=" + items.size()
                + ", full=" + full()
                + ", decision=" + (decision == null ? "null" : decision.approvedIndexes())
                + ']';
    }
}
