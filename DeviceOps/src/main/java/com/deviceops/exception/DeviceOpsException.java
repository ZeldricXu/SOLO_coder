package com.deviceops.exception;

public class DeviceOpsException extends RuntimeException {

    private final Integer code;

    public DeviceOpsException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }

    public static DeviceOpsException deviceNotFound(String deviceId) {
        return new DeviceOpsException(404, "设备不存在: " + deviceId);
    }

    public static DeviceOpsException faultNotFound(String faultId) {
        return new DeviceOpsException(404, "故障不存在: " + faultId);
    }

    public static DeviceOpsException taskNotFound(String taskId) {
        return new DeviceOpsException(404, "任务不存在: " + taskId);
    }

    public static DeviceOpsException operatorNotFound(String operatorId) {
        return new DeviceOpsException(404, "运维人员不存在: " + operatorId);
    }

    public static DeviceOpsException noAvailableOperator() {
        return new DeviceOpsException(500, "没有可用的运维人员");
    }

    public static DeviceOpsException taskAlreadyCompleted() {
        return new DeviceOpsException(400, "任务已完成");
    }

    public static DeviceOpsException invalidStatus(String status) {
        return new DeviceOpsException(400, "无效的状态: " + status);
    }
}
