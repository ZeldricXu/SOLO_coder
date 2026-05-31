package com.edgeplatform.common.util;

import java.util.UUID;

public class IdGenerator {

    private static final String PREFIX_CONFIG = "cfg_";
    private static final String PREFIX_DEVICE = "dev_";
    private static final String PREFIX_RUN = "run_";
    private static final String PREFIX_SNAPSHOT = "snap_";
    private static final String PREFIX_RESOURCE = "rsc_";
    private static final String PREFIX_BATCH = "batch_";
    private static final String PREFIX_MODEL = "model_";
    private static final String PREFIX_TASK = "task_";
    private static final String PREFIX_NOTIFICATION = "notif_";
    private static final String PREFIX_OBJECT = "obj_";
    private static final String PREFIX_RULE = "rule_";
    private static final String PREFIX_SHADOW = "shadow_";

    private IdGenerator() {
    }

    public static String generateConfigId() {
        return PREFIX_CONFIG + shortUuid();
    }

    public static String generateDeviceId() {
        return PREFIX_DEVICE + shortUuid();
    }

    public static String generateRunId() {
        return PREFIX_RUN + shortUuid();
    }

    public static String generateSnapshotId() {
        return PREFIX_SNAPSHOT + shortUuid();
    }

    public static String generateResourceId() {
        return PREFIX_RESOURCE + shortUuid();
    }

    public static String generateBatchId() {
        return PREFIX_BATCH + shortUuid();
    }

    public static String generateModelId() {
        return PREFIX_MODEL + shortUuid();
    }

    public static String generateTaskId() {
        return PREFIX_TASK + shortUuid();
    }

    public static String generateNotificationId() {
        return PREFIX_NOTIFICATION + shortUuid();
    }

    public static String generateObjectId() {
        return PREFIX_OBJECT + shortUuid();
    }

    public static String generateRuleId() {
        return PREFIX_RULE + shortUuid();
    }

    public static String generateShadowId() {
        return PREFIX_SHADOW + shortUuid();
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
