package com.deviceops.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger deviceCounter = new AtomicInteger(1);
    private static final AtomicInteger statusCounter = new AtomicInteger(1);
    private static final AtomicInteger faultCounter = new AtomicInteger(1);
    private static final AtomicInteger taskCounter = new AtomicInteger(1);
    private static final AtomicInteger operatorCounter = new AtomicInteger(1);
    private static final AtomicInteger alertCounter = new AtomicInteger(1);
    private static final AtomicInteger statCounter = new AtomicInteger(1);
    private static final AtomicInteger typeCounter = new AtomicInteger(1);
    private static final AtomicInteger historyCounter = new AtomicInteger(1);

    private IdGenerator() {
    }

    public static String generateDeviceId() {
        return "device_" + String.format("%03d", deviceCounter.getAndIncrement());
    }

    public static String generateStatusId() {
        return "status_" + String.format("%03d", statusCounter.getAndIncrement());
    }

    public static String generateFaultId() {
        return "fault_" + String.format("%03d", faultCounter.getAndIncrement());
    }

    public static String generateTaskId() {
        return "task_" + String.format("%03d", taskCounter.getAndIncrement());
    }

    public static String generateOperatorId() {
        return "operator_" + String.format("%03d", operatorCounter.getAndIncrement());
    }

    public static String generateAlertId() {
        return "alert_" + String.format("%03d", alertCounter.getAndIncrement());
    }

    public static String generateStatId() {
        return "stat_" + String.format("%03d", statCounter.getAndIncrement());
    }

    public static String generateTypeId() {
        return "type_" + String.format("%03d", typeCounter.getAndIncrement());
    }

    public static String generateHistoryId() {
        return "history_" + String.format("%03d", historyCounter.getAndIncrement());
    }

    public static String generateUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
