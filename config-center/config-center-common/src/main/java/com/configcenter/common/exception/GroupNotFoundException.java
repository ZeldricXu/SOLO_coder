package com.configcenter.common.exception;

public class GroupNotFoundException extends BusinessException {

    public GroupNotFoundException(String groupId) {
        super(404, "分组不存在: " + groupId);
    }

    public GroupNotFoundException(String groupName, String environment) {
        super(404, "分组不存在: groupName=" + groupName + ", environment=" + environment);
    }
}
