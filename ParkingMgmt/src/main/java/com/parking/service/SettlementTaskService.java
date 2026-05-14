package com.parking.service;

import com.parking.entity.EntryRecord;
import com.parking.entity.ExitRecord;
import com.parking.entity.SettlementRecord;
import com.parking.entity.SettlementTask;
import com.parking.exception.ParkingException;
import com.parking.repository.SettlementTaskRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SettlementTaskService {

    @Autowired
    private SettlementTaskRepository settlementTaskRepository;

    @Autowired
    private EntryService entryService;

    @Autowired
    private ExitService exitService;

    @Autowired
    private SettlementService settlementService;

    private final Map<String, SettlementTask> inMemoryQueue = new ConcurrentHashMap<>();
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    @Transactional
    public SettlementTask createTask(EntryRecord entryRecord, ExitRecord exitRecord) {
        if (hasPendingTask(entryRecord.getEntryId())) {
            throw new ParkingException(400, "该入场记录已有待处理的结算任务");
        }

        SettlementTask task = new SettlementTask();
        task.setTaskId(IdGenerator.generateSettlementId());
        task.setEntryId(entryRecord.getEntryId());
        task.setExitId(exitRecord.getExitId());
        task.setVehicleId(entryRecord.getVehicleId());
        task.setSpaceId(entryRecord.getSpaceId());
        task.setParkingDurationMinutes(exitRecord.getParkingDuration());
        task.setTaskStatus("pending");
        task.setRetryAttempts(0);
        task.setMaxRetryAttempts(3);

        SettlementTask savedTask = settlementTaskRepository.save(task);
        inMemoryQueue.put(savedTask.getTaskId(), savedTask);

        return savedTask;
    }

    public boolean hasPendingTask(String entryId) {
        List<String> pendingStatuses = Arrays.asList("pending", "processing", "retry");
        return settlementTaskRepository.existsByEntryIdAndTaskStatusIn(entryId, pendingStatuses);
    }

    @Transactional
    public SettlementTask getTaskById(String taskId) {
        return settlementTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new ParkingException(404, "结算任务不存在: " + taskId));
    }

    public List<SettlementTask> getAllPendingTasks() {
        return settlementTaskRepository.findAllPendingTasks();
    }

    public long countPendingTasks() {
        return settlementTaskRepository.countByTaskStatus("pending") 
               + settlementTaskRepository.countByTaskStatus("retry");
    }

    @Transactional
    public SettlementTask updateTaskStatus(String taskId, String status) {
        SettlementTask task = getTaskById(taskId);
        task.setTaskStatus(status);
        
        if ("completed".equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
        } else if ("processing".equals(status)) {
            task.setStartedAt(LocalDateTime.now());
        }

        return settlementTaskRepository.save(task);
    }

    @Transactional
    public SettlementTask markForRetry(String taskId, String errorMessage) {
        SettlementTask task = getTaskById(taskId);
        
        if (!task.canRetry()) {
            task.setTaskStatus("failed");
            task.setErrorMessage(errorMessage);
            return settlementTaskRepository.save(task);
        }

        task.incrementRetryCount();
        task.setTaskStatus("retry");
        task.setErrorMessage(errorMessage);
        task.setNextRetryAt(LocalDateTime.now().plusSeconds(30L * task.getRetryAttempts()));

        return settlementTaskRepository.save(task);
    }

    public void enqueueTask(SettlementTask task) {
        inMemoryQueue.put(task.getTaskId(), task);
    }

    public SettlementTask dequeueTask() {
        if (inMemoryQueue.isEmpty()) {
            List<SettlementTask> pendingTasks = settlementTaskRepository.findAllPendingTasks();
            for (SettlementTask task : pendingTasks) {
                inMemoryQueue.putIfAbsent(task.getTaskId(), task);
            }
        }

        if (!inMemoryQueue.isEmpty()) {
            Optional<Map.Entry<String, SettlementTask>> firstEntry = inMemoryQueue.entrySet().stream()
                    .filter(entry -> "pending".equals(entry.getValue().getTaskStatus()) || 
                                    "retry".equals(entry.getValue().getTaskStatus()))
                    .findFirst();
            
            if (firstEntry.isPresent()) {
                SettlementTask task = firstEntry.get().getValue();
                inMemoryQueue.remove(task.getTaskId());
                return task;
            }
        }

        return null;
    }

    @Transactional
    public void processTask(SettlementTask task) {
        try {
            updateTaskStatus(task.getTaskId(), "processing");

            EntryRecord entryRecord = entryService.getEntryById(task.getEntryId());
            ExitRecord exitRecord = exitService.getExitByEntryId(task.getEntryId());

            SettlementRecord settlement = settlementService.createSettlement(entryRecord, exitRecord);
            settlementService.retryPayment(settlement.getSettlementId());

            updateTaskStatus(task.getTaskId(), "completed");

        } catch (Exception e) {
            markForRetry(task.getTaskId(), e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void processPendingTasks() {
        if (!workerRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            while (true) {
                SettlementTask task = dequeueTask();
                if (task == null) {
                    break;
                }
                processTask(task);
            }
        } finally {
            workerRunning.set(false);
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void recoverFailedTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<SettlementTask> retryTasks = settlementTaskRepository.findPendingRetryTasks("retry", now);
        
        for (SettlementTask task : retryTasks) {
            inMemoryQueue.putIfAbsent(task.getTaskId(), task);
        }
    }

    @Transactional
    public void recoverAllPendingTasksOnStartup() {
        List<SettlementTask> inProgressTasks = settlementTaskRepository.findByTaskStatus("processing");
        for (SettlementTask task : inProgressTasks) {
            markForRetry(task.getTaskId(), "服务重启中，恢复任务");
        }

        List<SettlementTask> pendingTasks = settlementTaskRepository.findAllPendingTasks();
        for (SettlementTask task : pendingTasks) {
            inMemoryQueue.putIfAbsent(task.getTaskId(), task);
        }
    }
}
