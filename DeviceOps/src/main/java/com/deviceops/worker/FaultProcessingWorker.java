package com.deviceops.worker;

import com.deviceops.entity.FaultRecord;
import com.deviceops.queue.FaultQueueService;
import com.deviceops.queue.FaultTaskDTO;
import com.deviceops.service.alert.AlertService;
import com.deviceops.service.task.TaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FaultProcessingWorker {

    @Autowired
    private FaultQueueService faultQueueService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private AlertService alertService;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int WORKER_THREAD_COUNT = 4;

    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(WORKER_THREAD_COUNT);
        recoverUnfinishedTasks();
        startWorkerThreads();
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }

    private void startWorkerThreads() {
        for (int i = 0; i < WORKER_THREAD_COUNT; i++) {
            executorService.submit(this::workerLoop);
        }
    }

    private void workerLoop() {
        while (running.get()) {
            try {
                FaultTaskDTO task = faultQueueService.dequeueFaultTask();
                if (task != null) {
                    processFaultTask(task);
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Worker处理故障任务失败: " + e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processFaultTask(FaultTaskDTO task) {
        try {
            FaultRecord fault = convertToFaultRecord(task);
            createTaskFromFault(fault);
            sendAlertForFault(fault);
            faultQueueService.acknowledgeProcessing(task.getFaultId());
        } catch (Exception e) {
            System.err.println("处理故障任务失败: " + task.getFaultId() + ", 错误: " + e.getMessage());
            faultQueueService.moveToDeadLetter(task);
        }
    }

    private FaultRecord convertToFaultRecord(FaultTaskDTO dto) {
        FaultRecord fault = new FaultRecord();
        fault.setFaultId(dto.getFaultId());
        fault.setDeviceId(dto.getDeviceId());
        fault.setFaultType(dto.getFaultType());
        fault.setFaultLevel(dto.getFaultLevel());
        fault.setFaultDesc(dto.getFaultDesc());
        fault.setReportedBy(dto.getReportedBy());
        fault.setReportedAt(dto.getReportedAt());
        fault.setFaultStatus(dto.getTaskStatus() != null ? dto.getTaskStatus() : "pending");
        return fault;
    }

    private void createTaskFromFault(FaultRecord fault) {
        String priority = fault.getFaultLevel();
        switch (priority) {
            case "high":
                taskService.createHighPriorityTask(fault);
                break;
            case "low":
                taskService.createLowPriorityTask(fault);
                break;
            default:
                taskService.createMediumPriorityTask(fault);
        }
    }

    private void sendAlertForFault(FaultRecord fault) {
        alertService.sendAlertForFault(fault.getDeviceId(), fault);
    }

    private void recoverUnfinishedTasks() {
        List<FaultTaskDTO> recovered = faultQueueService.recoverProcessingTasks();
        if (!recovered.isEmpty()) {
            System.out.println("恢复未完成的故障任务: " + recovered.size() + " 个");
            for (FaultTaskDTO task : recovered) {
                faultQueueService.enqueueFaultTask(task);
            }
        }
    }

    @Scheduled(fixedRate = 30000)
    public void monitorQueueStatus() {
        long queueSize = faultQueueService.getQueueSize();
        long processingCount = faultQueueService.getProcessingCount();
        if (queueSize > 0 || processingCount > 0) {
            System.out.println("[队列监控] 等待处理: " + queueSize + ", 处理中: " + processingCount);
        }
    }

    public int getWorkerThreadCount() {
        return WORKER_THREAD_COUNT;
    }

    public boolean isRunning() {
        return running.get();
    }
}
