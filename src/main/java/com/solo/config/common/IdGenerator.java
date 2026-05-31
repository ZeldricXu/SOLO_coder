package com.solo.config.common;

import java.util.UUID;

public class IdGenerator {

    private IdGenerator() {
    }

    public static String generate(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String generateResourceId() {
        return generate("rsc");
    }

    public static String generateConfigId() {
        return generate("cfg");
    }

    public static String generateRunId() {
        return generate("run");
    }

    public static String generateSnapshotId() {
        return generate("snap");
    }

    public static String generateEventId() {
        return generate("evt");
    }

    public static String generateCommandId() {
        return generate("cmd");
    }

    public static String generateAuditId() {
        return generate("audit");
    }

    public static String generateNotificationId() {
        return generate("notif");
    }

    public static String generatePolicyId() {
        return generate("pol");
    }

    public static String generateInstanceId() {
        return generate("inst");
    }
}
