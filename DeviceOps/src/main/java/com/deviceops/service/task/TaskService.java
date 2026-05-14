package com.deviceops.service.task;

import com.deviceops.entity.FaultRecord;
import com.deviceops.entity.OperationTask;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.OperationTaskRepository;
import com.deviceops.service.analysis.AnalysisService;
import com.deviceops.service.config.DynamicConfigService;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.fault.FaultService;
import com.deviceops.service.history.HistoryService;
import com.deviceops.service.operator.OperatorService;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TaskService {

    @Autowired
    private OperationTaskRepository taskRepository;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private OperatorService operatorService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private FaultService faultService;

    @Autowired
    private DynamicConfigService dynamicConfigService;

    @Transactional
    public OperationTask createTaskFromFault(FaultRecord fault) {
        if (!deviceService.exists(fault.getDeviceId())) {
            throw DeviceOpsException.deviceNotFound(fault.getDeviceId());
        }

        OperationTask task = new OperationTask();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setFaultId(fault.getFaultId());
        task.setDeviceId(fault.getDeviceId());
        task.setTaskType("repair");
        task.setTaskStatus("pending");

        Optional<com.deviceops.entity.Operator> operator = operatorService.findOptimalOperator(fault.getFaultType());
        if (operator.isPresent()) {
            task.setOperatorId(operator.get().getOperatorId());
            task.setTaskStatus("assigned");
        }

        OperationTask saved = taskRepository.save(task);

        historyService.recordTaskCreate(fault.getDeviceId(), saved.getTaskId(), fault.getFaultId());

        analysisService.incrementTaskCount();

        return saved;
    }

    public OperationTask getTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> DeviceOpsException.taskNotFound(taskId));
    }

    public List<OperationTask> getAllTasks() {
        return taskRepository.findAll();
    }

    public List<OperationTask> getTasksByDevice(String deviceId) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }
        return taskRepository.findByDeviceIdOrderByTaskTimeDesc(deviceId);
    }

    public List<OperationTask> getTasksByOperator(String operatorId) {
        return taskRepository.findByOperatorId(operatorId);
    }

    public List<OperationTask> getTasksByStatus(String status) {
        return taskRepository.findByTaskStatus(status);
    }

    public List<OperationTask> getTasksByFault(String faultId) {
        return taskRepository.findByFaultId(faultId);
    }

    @Transactional
    public OperationTask executeTask(String taskId, String operatorId) {
        OperationTask task = getTask(taskId);

        if ("completed".equals(task.getTaskStatus())) {
            throw DeviceOpsException.taskAlreadyCompleted();
        }

        if (task.getOperatorId() == null && operatorId != null) {
            task.setOperatorId(operatorId);
        }

        task.setTaskStatus("processing");

        OperationTask saved = taskRepository.save(task);

        historyService.recordTaskExecute(task.getDeviceId(), taskId, saved.getOperatorId());

        return saved;
    }

    @Transactional
    public OperationTask completeTask(String taskId, String result) {
        OperationTask task = getTask(taskId);

        if ("completed".equals(task.getTaskStatus())) {
            throw DeviceOpsException.taskAlreadyCompleted();
        }

        task.setTaskStatus("completed");
        task.setCompletedAt(LocalDateTime.now());
        task.setResult(result != null ? result : "任务完成");

        OperationTask saved = taskRepository.save(task);

        if (task.getOperatorId() != null) {
            operatorService.incrementCompletedCount(task.getOperatorId());
            operatorService.releaseOperator(task.getOperatorId());
        }

        if (task.getFaultId() != null) {
            faultService.resolveFault(task.getFaultId(), task.getOperatorId());
        }

        historyService.recordTaskComplete(task.getDeviceId(), taskId, task.getOperatorId(), task.getFaultId());

        analysisService.updateStatistics();

        return saved;
    }

    @Transactional
    public OperationTask updateTaskStatus(String taskId, String status) {
        OperationTask task = getTask(taskId);
        task.setTaskStatus(status);
        return taskRepository.save(task);
    }

    public long countByStatus(String status) {
        return taskRepository.countByTaskStatus(status);
    }

    public long count() {
        return taskRepository.count();
    }

    @Transactional
    public OperationTask lockTask(String taskId, String operatorId) {
        OperationTask task = getTask(taskId);

        if ("completed".equals(task.getTaskStatus())) {
            throw DeviceOpsException.taskAlreadyCompleted();
        }

        if (Boolean.TRUE.equals(task.getIsLocked()) && !isLockExpired(task)) {
            throw new RuntimeException("任务已被锁定，请稍后重试");
        }

        String priority = task.getPriorityLevel() != null ? task.getPriorityLevel() : "medium";
        int timeout = dynamicConfigService.getTaskLockTimeout(priority);

        task.setIsLocked(true);
        task.setLockedAt(LocalDateTime.now());
        task.setLockedBy(operatorId);
        task.setLockTimeoutSeconds(timeout);

        return taskRepository.save(task);
    }

    @Transactional
    public OperationTask unlockTask(String taskId) {
        OperationTask task = getTask(taskId);
        task.setIsLocked(false);
        task.setLockedAt(null);
        task.setLockedBy(null);
        return taskRepository.save(task);
    }

    @Transactional
    public boolean tryLockTask(String taskId, String operatorId) {
        try {
            lockTask(taskId, operatorId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTaskLocked(String taskId) {
        OperationTask task = getTask(taskId);
        return Boolean.TRUE.equals(task.getIsLocked()) && !isLockExpired(task);
    }

    public boolean isLockExpired(OperationTask task) {
        if (task.getLockedAt() == null || task.getLockTimeoutSeconds() == null) {
            return false;
        }
        LocalDateTime expireTime = task.getLockedAt().plusSeconds(task.getLockTimeoutSeconds());
        return LocalDateTime.now().isAfter(expireTime);
    }

    public int getLockTimeoutByPriority(String priority) {
        return dynamicConfigService.getTaskLockTimeout(priority);
    }

    @Transactional
    public OperationTask createTaskWithPriority(FaultRecord fault, String priority) {
        OperationTask task = createTaskFromFault(fault);
        task.setPriorityLevel(priority);
        task.setLockTimeoutSeconds(dynamicConfigService.getTaskLockTimeout(priority));
        return taskRepository.save(task);
    }

    @Transactional
    public OperationTask createHighPriorityTask(FaultRecord fault) {
        return createTaskWithPriority(fault, "high");
    }

    @Transactional
    public OperationTask createMediumPriorityTask(FaultRecord fault) {
        return createTaskWithPriority(fault, "medium");
    }

    @Transactional
    public OperationTask createLowPriorityTask(FaultRecord fault) {
        return createTaskWithPriority(fault, "low");
    }
}
