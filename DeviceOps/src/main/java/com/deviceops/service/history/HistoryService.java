package com.deviceops.service.history;

import com.deviceops.entity.OperationHistory;
import com.deviceops.repository.OperationHistoryRepository;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private OperationHistoryRepository historyRepository;

    @Transactional
    public OperationHistory recordOperation(String deviceId, String operationType, String operationDesc) {
        return recordOperation(deviceId, operationType, operationDesc, null, null, null);
    }

    @Transactional
    public OperationHistory recordOperation(String deviceId, String operationType, String operationDesc,
                                            String operatorId, String taskId, String faultId) {
        OperationHistory history = new OperationHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setDeviceId(deviceId);
        history.setOperationType(operationType);
        history.setOperationDesc(operationDesc);
        history.setOperatorId(operatorId);
        history.setTaskId(taskId);
        history.setFaultId(faultId);
        return historyRepository.save(history);
    }

    public List<OperationHistory> getHistoryByDevice(String deviceId) {
        return historyRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    public List<OperationHistory> getHistoryByOperator(String operatorId) {
        return historyRepository.findByOperatorIdOrderByCreatedAtDesc(operatorId);
    }

    public List<OperationHistory> getHistoryByTask(String taskId) {
        return historyRepository.findByTaskId(taskId);
    }

    public List<OperationHistory> getHistoryByFault(String faultId) {
        return historyRepository.findByFaultId(faultId);
    }

    public List<OperationHistory> getHistoryByType(String operationType) {
        return historyRepository.findByOperationTypeOrderByCreatedAtDesc(operationType);
    }

    public List<OperationHistory> getHistoryByTimeRange(LocalDateTime start, LocalDateTime end) {
        return historyRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);
    }

    public List<OperationHistory> getAllHistory() {
        return historyRepository.findAll();
    }

    @Transactional
    public OperationHistory recordDeviceCreate(String deviceId, String deviceName) {
        return recordOperation(deviceId, "DEVICE_CREATE", "创建设备: " + deviceName);
    }

    @Transactional
    public OperationHistory recordDeviceStatusUpdate(String deviceId, String oldStatus, String newStatus) {
        return recordOperation(deviceId, "DEVICE_STATUS_UPDATE", 
                "设备状态更新: " + oldStatus + " -> " + newStatus);
    }

    @Transactional
    public OperationHistory recordMonitor(String deviceId, String status) {
        return recordOperation(deviceId, "MONITOR", "设备监控执行, 当前状态: " + status);
    }

    @Transactional
    public OperationHistory recordFaultReport(String deviceId, String faultId, String faultDesc) {
        return recordOperation(deviceId, "FAULT_REPORT", "故障上报: " + faultDesc, null, null, faultId);
    }

    @Transactional
    public OperationHistory recordTaskCreate(String deviceId, String taskId, String faultId) {
        return recordOperation(deviceId, "TASK_CREATE", "创建运维任务", null, taskId, faultId);
    }

    @Transactional
    public OperationHistory recordTaskExecute(String deviceId, String taskId, String operatorId) {
        return recordOperation(deviceId, "TASK_EXECUTE", "执行运维任务", operatorId, taskId, null);
    }

    @Transactional
    public OperationHistory recordTaskComplete(String deviceId, String taskId, String operatorId, String faultId) {
        return recordOperation(deviceId, "TASK_COMPLETE", "完成运维任务", operatorId, taskId, faultId);
    }

    @Transactional
    public OperationHistory recordFaultRepair(String deviceId, String faultId, String operatorId) {
        return recordOperation(deviceId, "FAULT_REPAIR", "故障修复完成", operatorId, null, faultId);
    }

    @Transactional
    public OperationHistory recordAlert(String deviceId, String alertType, String alertLevel) {
        return recordOperation(deviceId, "ALERT", "设备预警: 类型=" + alertType + ", 级别=" + alertLevel);
    }
}
