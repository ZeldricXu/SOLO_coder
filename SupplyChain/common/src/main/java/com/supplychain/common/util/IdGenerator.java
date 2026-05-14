package com.supplychain.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.simpleUUID();
    }

    public static String generateSupplierId() {
        return generateId("supplier");
    }

    public static String generateOrderId() {
        return generateId("order");
    }

    public static String generateSyncId() {
        return generateId("sync");
    }

    public static String generateWarningId() {
        return generateId("warning");
    }

    public static String generateTrackingId() {
        return generateId("tracking");
    }

    public static String generateStatId() {
        return generateId("stat");
    }

    public static String generateContractId() {
        return generateId("contract");
    }

    public static String generateMessageId() {
        return generateId("message");
    }

    public static String generateRecordId() {
        return generateId("record");
    }

    public static String generateInventoryId() {
        return generateId("inventory");
    }

    public static String generateEvaluationId() {
        return generateId("evaluation");
    }
}
