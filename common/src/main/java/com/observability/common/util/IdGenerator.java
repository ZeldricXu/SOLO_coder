package com.observability.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    private IdGenerator() {
    }

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.fastSimpleUUID().substring(0, 8);
    }

    public static String generateResourceId() {
        return generateId("rsc");
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

    public static String generateTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateBatchId() {
        return generateId("batch");
    }

    public static String generateAlertId() {
        return generateId("alert");
    }

    public static String generateJobId() {
        return generateId("job");
    }
}
