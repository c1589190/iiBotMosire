package io.mosire.iibot.taskflow.decision;

import java.util.Objects;

/** PACKAGE_COMMAND_STARTED/APPLIED/FAILED 的完整内容。 */
public record PackageCommandEvent(Phase phase, PackageCommand command, String failureCode) {

    public enum Phase {
        STARTED,
        APPLIED,
        FAILED
    }

    public PackageCommandEvent {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(command, "command");
        if (phase == Phase.FAILED) {
            if (failureCode == null || failureCode.isBlank()) {
                throw new IllegalArgumentException("FAILED phase 必须提供 failureCode");
            }
        } else if (failureCode != null) {
            throw new IllegalArgumentException("非 FAILED phase 不能提供 failureCode");
        }
    }

    @Override
    public String toString() {
        return "PackageCommandEvent{phase=" + phase + ", command=" + command
                + ", failureCode=" + failureCode + '}';
    }
}
