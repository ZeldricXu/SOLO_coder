package com.solocoder.dns.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {
    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.fastSimpleUUID().substring(0, 12);
    }

    public static String generateTraceId() {
        return "trace_" + IdUtil.fastSimpleUUID();
    }

    public static String generateEntityId() {
        return generateId("ent");
    }

    public static String generateConfigId() {
        return generateId("cfg");
    }

    public static String generateRunId() {
        return generateId("run");
    }

    public static String generateEventId() {
        return generateId("evt");
    }

    public static String generateCommandId() {
        return generateId("cmd");
    }

    public static String generateSnapshotId() {
        return generateId("snap");
    }
}
