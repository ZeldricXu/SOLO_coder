package com.edgescheduler.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.nanoId(12);
    }

    public static String generateResourceId() {
        return generateId("rsc");
    }

    public static String generateBatchId() {
        return generateId("batch");
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

    public static String generateSnapshotId() {
        return generateId("snap");
    }

    public static String generateDeviceId() {
        return generateId("dev");
    }

    public static String generateTaskId() {
        return generateId("task");
    }

    public static String generateRuleId() {
        return generateId("rule");
    }

    public static String generateFirmwareId() {
        return generateId("fw");
    }

    public static String generateModelId() {
        return generateId("model");
    }

    public static long generateSnowflakeId() {
        return IdUtil.getSnowflakeNextId();
    }
}
