package io.mosire.iibot.connect.core;

import java.util.Objects;

/**
 * QQ 群成员摘要；只用于控制方定位玩家，不包含私聊正文。
 *
 * @param groupId     群号
 * @param qqId        成员 QQ 号
 * @param displayName 昵称
 * @param card        群名片；无则为空串
 * @param role        群角色（owner/admin/member 等，取协议返回值）
 */
public record GroupMember(String groupId, String qqId, String displayName,
                          String card, String role) {

    public GroupMember {
        groupId = requireText(groupId, "groupId");
        qqId = requireText(qqId, "qqId");
        displayName = displayName == null ? "" : displayName;
        card = card == null ? "" : card;
        role = role == null ? "" : role;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
