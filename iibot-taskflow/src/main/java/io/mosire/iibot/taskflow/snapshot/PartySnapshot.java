package io.mosire.iibot.taskflow.snapshot;

import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;

import java.util.Objects;

/** 已注册身份及其领域角色的纯数据快照。 */
public record PartySnapshot(PlayerProfile profile, ActorRole role) {
    public PartySnapshot {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(role, "role");
    }
}
