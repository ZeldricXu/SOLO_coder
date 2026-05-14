package com.configcenter.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    private IdGenerator() {
    }

    public static String generateConfigId(String prefix) {
        return prefix + "_" + IdUtil.simpleUUID().substring(0, 8);
    }

    public static String generateVersionId(String configId, String version) {
        return "ver_" + configId + "_" + version;
    }

    public static String generatePushId() {
        return "push_" + IdUtil.simpleUUID().substring(0, 6);
    }

    public static String generateAuditId() {
        return "audit_" + IdUtil.simpleUUID().substring(0, 6);
    }

    public static String generateGroupId(String prefix) {
        return "group_" + prefix + "_" + IdUtil.simpleUUID().substring(0, 6);
    }

    public static String generateInstanceId() {
        return "instance_" + IdUtil.simpleUUID().substring(0, 8);
    }

    public static String simpleUUID() {
        return IdUtil.simpleUUID();
    }
}
