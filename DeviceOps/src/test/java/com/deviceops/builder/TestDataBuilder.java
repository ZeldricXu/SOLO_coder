package com.deviceops.builder;

import com.deviceops.dto.DeviceCreateRequest;
import com.deviceops.dto.FaultReportRequest;
import com.deviceops.entity.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static Device buildDevice() {
        return buildDevice("device_001", "测试服务器", "server", "机房A-机架1");
    }

    public static Device buildDevice(String deviceId, String deviceName, String deviceType, String location) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceName);
        device.setDeviceType(deviceType);
        device.setDeviceLocation(location);
        device.setDeviceStatus("normal");
        device.setDeviceModel("Dell PowerEdge R750");
        device.setDeviceSn("SN" + System.currentTimeMillis());
        device.setCreatedAt(LocalDateTime.now());
        device.setUpdatedAt(LocalDateTime.now());
        return device;
    }

    public static Device buildNormalDevice() {
        return buildDevice("device_001", "正常服务器", "server", "机房A");
    }

    public static Device buildAbnormalDevice() {
        Device device = buildDevice("device_002", "异常服务器", "server", "机房B");
        device.setDeviceStatus("abnormal");
        return device;
    }

    public static Device buildWarningDevice() {
        Device device = buildDevice("device_003", "预警服务器", "server", "机房C");
        device.setDeviceStatus("warning");
        return device;
    }

    public static List<Device> buildDeviceList(int count) {
        List<Device> devices = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            devices.add(buildDevice(
                    "device_" + String.format("%03d", i),
                    "服务器" + i,
                    "server",
                    "机房A-机架" + i
            ));
        }
        return devices;
    }

    public static DeviceCreateRequest buildDeviceCreateRequest() {
        return buildDeviceCreateRequest("新设备", "server", "机房A");
    }

    public static DeviceCreateRequest buildDeviceCreateRequest(String name, String type, String location) {
        DeviceCreateRequest request = new DeviceCreateRequest();
        request.setDeviceName(name);
        request.setDeviceType(type);
        request.setDeviceLocation(location);
        request.setDeviceModel("Test Model");
        request.setDeviceSn("TEST-SN-001");
        return request;
    }

    public static StatusRecord buildStatusRecord() {
        return buildStatusRecord("status_001", "device_001", "cpu", 65, "normal");
    }

    public static StatusRecord buildStatusRecord(String statusId, String deviceId, String statusType, 
                                                  int value, String level) {
        StatusRecord record = new StatusRecord();
        record.setStatusId(statusId);
        record.setDeviceId(deviceId);
        record.setStatusType(statusType);
        record.setStatusValue(value);
        record.setStatusTime(LocalDateTime.now());
        record.setStatusLevel(level);
        return record;
    }

    public static StatusRecord buildNormalStatusRecord() {
        return buildStatusRecord("status_001", "device_001", "cpu", 50, "normal");
    }

    public static StatusRecord buildWarningStatusRecord() {
        return buildStatusRecord("status_002", "device_001", "cpu", 75, "warning");
    }

    public static StatusRecord buildAbnormalStatusRecord() {
        return buildStatusRecord("status_003", "device_001", "cpu", 95, "abnormal");
    }

    public static List<StatusRecord> buildStatusRecordList(String deviceId) {
        List<StatusRecord> records = new ArrayList<>();
        records.add(buildStatusRecord("status_001", deviceId, "cpu", 60, "normal"));
        records.add(buildStatusRecord("status_002", deviceId, "memory", 70, "warning"));
        records.add(buildStatusRecord("status_003", deviceId, "network", 40, "normal"));
        return records;
    }

    public static FaultRecord buildFaultRecord() {
        return buildFaultRecord("fault_001", "device_001", "hardware", "high", "硬件故障");
    }

    public static FaultRecord buildFaultRecord(String faultId, String deviceId, String faultType,
                                                String faultLevel, String desc) {
        FaultRecord fault = new FaultRecord();
        fault.setFaultId(faultId);
        fault.setDeviceId(deviceId);
        fault.setFaultType(faultType);
        fault.setFaultLevel(faultLevel);
        fault.setFaultDesc(desc);
        fault.setFaultStatus("pending");
        fault.setReportedAt(LocalDateTime.now());
        fault.setReportedBy("tester");
        return fault;
    }

    public static FaultRecord buildPendingFault() {
        return buildFaultRecord("fault_001", "device_001", "hardware", "high", "CPU过热");
    }

    public static FaultRecord buildProcessingFault() {
        FaultRecord fault = buildFaultRecord("fault_002", "device_001", "software", "medium", "系统崩溃");
        fault.setFaultStatus("processing");
        return fault;
    }

    public static FaultRecord buildResolvedFault() {
        FaultRecord fault = buildFaultRecord("fault_003", "device_001", "network", "low", "网络连接中断");
        fault.setFaultStatus("resolved");
        fault.setRepairedAt(LocalDateTime.now());
        return fault;
    }

    public static FaultRecord buildHighPriorityFault() {
        return buildFaultRecord("fault_004", "device_001", "hardware", "high", "紧急硬件故障");
    }

    public static FaultRecord buildMediumPriorityFault() {
        return buildFaultRecord("fault_005", "device_001", "software", "medium", "一般软件故障");
    }

    public static FaultRecord buildLowPriorityFault() {
        return buildFaultRecord("fault_006", "device_001", "network", "low", "轻微网络故障");
    }

    public static FaultReportRequest buildFaultReportRequest() {
        return buildFaultReportRequest("device_001", "hardware", "CPU温度过高");
    }

    public static FaultReportRequest buildFaultReportRequest(String deviceId, String type, String desc) {
        FaultReportRequest request = new FaultReportRequest();
        request.setDeviceId(deviceId);
        request.setFaultType(type);
        request.setFaultDesc(desc);
        request.setFaultLevel("high");
        request.setReportedBy("tester");
        return request;
    }

    public static FaultReportRequest buildHighPriorityFaultRequest() {
        FaultReportRequest request = buildFaultReportRequest();
        request.setFaultLevel("high");
        return request;
    }

    public static FaultReportRequest buildMediumPriorityFaultRequest() {
        FaultReportRequest request = buildFaultReportRequest();
        request.setFaultLevel("medium");
        return request;
    }

    public static OperationTask buildTask() {
        return buildTask("task_001", "fault_001", "device_001", "operator_001", "pending");
    }

    public static OperationTask buildTask(String taskId, String faultId, String deviceId,
                                           String operatorId, String status) {
        OperationTask task = new OperationTask();
        task.setTaskId(taskId);
        task.setFaultId(faultId);
        task.setDeviceId(deviceId);
        task.setOperatorId(operatorId);
        task.setTaskType("repair");
        task.setTaskStatus(status);
        task.setTaskTime(LocalDateTime.now());
        return task;
    }

    public static OperationTask buildPendingTask() {
        return buildTask("task_001", "fault_001", "device_001", "operator_001", "pending");
    }

    public static OperationTask buildAssignedTask() {
        return buildTask("task_002", "fault_001", "device_001", "operator_001", "assigned");
    }

    public static OperationTask buildProcessingTask() {
        return buildTask("task_003", "fault_001", "device_001", "operator_001", "processing");
    }

    public static OperationTask buildCompletedTask() {
        OperationTask task = buildTask("task_004", "fault_001", "device_001", "operator_001", "completed");
        task.setCompletedAt(LocalDateTime.now());
        task.setResult("任务完成");
        return task;
    }

    public static OperationTask buildLockedTask() {
        OperationTask task = buildPendingTask();
        task.setIsLocked(true);
        task.setLockedAt(LocalDateTime.now());
        task.setLockedBy("operator_001");
        task.setLockTimeoutSeconds(1800);
        return task;
    }

    public static OperationTask buildHighPriorityTask() {
        OperationTask task = buildPendingTask();
        task.setTaskId("task_high_001");
        task.setPriorityLevel("high");
        task.setLockTimeoutSeconds(1800);
        return task;
    }

    public static OperationTask buildMediumPriorityTask() {
        OperationTask task = buildPendingTask();
        task.setTaskId("task_medium_001");
        task.setPriorityLevel("medium");
        task.setLockTimeoutSeconds(3600);
        return task;
    }

    public static OperationTask buildLowPriorityTask() {
        OperationTask task = buildPendingTask();
        task.setTaskId("task_low_001");
        task.setPriorityLevel("low");
        task.setLockTimeoutSeconds(7200);
        return task;
    }

    public static OperationTask buildExpiredLockTask() {
        OperationTask task = buildLockedTask();
        task.setLockedAt(LocalDateTime.now().minusHours(3));
        return task;
    }

    public static OperationTask buildTaskWithPriority(String taskId, String priority) {
        OperationTask task = buildPendingTask();
        task.setTaskId(taskId);
        task.setPriorityLevel(priority);
        task.setLockTimeoutSeconds(determineTimeoutForPriority(priority));
        return task;
    }

    private static int determineTimeoutForPriority(String priority) {
        if ("high".equals(priority)) {
            return 1800;
        } else if ("medium".equals(priority)) {
            return 3600;
        } else {
            return 7200;
        }
    }

    public static Operator buildOperator() {
        return buildOperator("operator_001", "张三", "hardware", "available");
    }

    public static Operator buildOperator(String operatorId, String name, String type, String status) {
        Operator operator = new Operator();
        operator.setOperatorId(operatorId);
        operator.setOperatorName(name);
        operator.setOperatorType(type);
        operator.setOperatorStatus(status);
        operator.setOperatorCount(0);
        operator.setCreatedAt(LocalDateTime.now());
        operator.setUpdatedAt(LocalDateTime.now());
        return operator;
    }

    public static Operator buildAvailableOperator() {
        return buildOperator("operator_001", "张三", "hardware", "available");
    }

    public static Operator buildBusyOperator() {
        return buildOperator("operator_002", "李四", "software", "busy");
    }

    public static Operator buildHardwareOperator() {
        return buildOperator("operator_003", "王五", "hardware", "available");
    }

    public static Operator buildSoftwareOperator() {
        return buildOperator("operator_004", "赵六", "software", "available");
    }

    public static List<Operator> buildOperatorList() {
        List<Operator> operators = new ArrayList<>();
        operators.add(buildAvailableOperator());
        operators.add(buildHardwareOperator());
        operators.add(buildSoftwareOperator());
        return operators;
    }

    public static AlertRecord buildAlertRecord() {
        return buildAlertRecord("alert_001", "device_001", "status_abnormal", "high");
    }

    public static AlertRecord buildAlertRecord(String alertId, String deviceId, String alertType, String level) {
        AlertRecord alert = new AlertRecord();
        alert.setAlertId(alertId);
        alert.setDeviceId(deviceId);
        alert.setAlertType(alertType);
        alert.setAlertLevel(level);
        alert.setAlertStatus("sent");
        alert.setAlertTime(LocalDateTime.now());
        alert.setAcknowledged(false);
        return alert;
    }

    public static AlertRecord buildUnacknowledgedAlert() {
        return buildAlertRecord("alert_001", "device_001", "status_abnormal", "high");
    }

    public static AlertRecord buildAcknowledgedAlert() {
        AlertRecord alert = buildAlertRecord("alert_002", "device_001", "status_warning", "medium");
        alert.setAcknowledged(true);
        return alert;
    }

    public static AlertRecord buildHighLevelAlert() {
        return buildAlertRecord("alert_003", "device_001", "status_abnormal", "high");
    }

    public static AlertRecord buildMediumLevelAlert() {
        return buildAlertRecord("alert_004", "device_001", "status_warning", "medium");
    }

    public static AlertRecord buildLowLevelAlert() {
        return buildAlertRecord("alert_005", "device_001", "status_info", "low");
    }

    public static AlertRecord buildAlertWithRetryCount(String alertId, String deviceId, 
                                                     String level, int retryCount, int maxRetries) {
        AlertRecord alert = buildAlertRecord(alertId, deviceId, "status_abnormal", level);
        alert.setRetryCount(retryCount);
        alert.setMaxRetries(maxRetries);
        return alert;
    }

    public static AlertRecord buildHighLevelAlertWithMaxRetries() {
        return buildAlertWithRetryCount("alert_006", "device_001", "high", 0, 5);
    }

    public static AlertRecord buildMediumLevelAlertWithMaxRetries() {
        return buildAlertWithRetryCount("alert_007", "device_001", "medium", 0, 3);
    }

    public static AlertRecord buildAlertExceededRetries() {
        AlertRecord alert = buildAlertWithRetryCount("alert_008", "device_001", "high", 5, 5);
        alert.setAlertStatus("failed");
        return alert;
    }

    public static DeviceType buildDeviceType() {
        return buildDeviceType("type_001", "server", "服务器", "服务器类设备");
    }

    public static DeviceType buildDeviceType(String typeId, String code, String name, String desc) {
        DeviceType type = new DeviceType();
        type.setTypeId(typeId);
        type.setTypeCode(code);
        type.setTypeName(name);
        type.setTypeDesc(desc);
        type.setCreatedAt(LocalDateTime.now());
        return type;
    }

    public static OperationHistory buildOperationHistory() {
        return buildOperationHistory("history_001", "device_001", "MONITOR", "设备监控执行");
    }

    public static OperationHistory buildOperationHistory(String historyId, String deviceId, 
                                                         String operationType, String desc) {
        OperationHistory history = new OperationHistory();
        history.setHistoryId(historyId);
        history.setDeviceId(deviceId);
        history.setOperationType(operationType);
        history.setOperationDesc(desc);
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    public static DeviceStatistics buildDeviceStatistics() {
        return buildDeviceStatistics("stat_001", "2026-05", 100, 20, 25, 2.5);
    }

    public static DeviceStatistics buildDeviceStatistics(String statId, String month,
                                                          int deviceCount, int faultCount,
                                                          int taskCount, double avgResponseTime) {
        DeviceStatistics stats = new DeviceStatistics();
        stats.setStatId(statId);
        stats.setStatMonth(month);
        stats.setDeviceCount(deviceCount);
        stats.setFaultCount(faultCount);
        stats.setTaskCount(taskCount);
        stats.setAvgResponseTime(avgResponseTime);
        return stats;
    }
}
