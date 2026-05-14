package com.medical.appointment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AsyncQuotaService.class);

    private final ScheduleService scheduleService;
    private final ExecutorService executorService;
    
    private final BlockingQueue<QuotaTask> taskQueue = new LinkedBlockingQueue<>();
    private final List<QuotaTaskResult> completedTasks = new CopyOnWriteArrayList<>();
    
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private final AtomicInteger successCounter = new AtomicInteger(0);
    private final AtomicInteger failureCounter = new AtomicInteger(0);

    public static class QuotaTask {
        private final String taskId;
        private final String scheduleId;
        private final String appointmentId;
        private final String taskType;
        private final LocalDateTime submittedAt;
        private final int quotaChange;

        public QuotaTask(String taskId, String scheduleId, String appointmentId, 
                        String taskType, int quotaChange) {
            this.taskId = taskId;
            this.scheduleId = scheduleId;
            this.appointmentId = appointmentId;
            this.taskType = taskType;
            this.quotaChange = quotaChange;
            this.submittedAt = LocalDateTime.now();
        }

        public String getTaskId() {
            return taskId;
        }

        public String getScheduleId() {
            return scheduleId;
        }

        public String getAppointmentId() {
            return appointmentId;
        }

        public String getTaskType() {
            return taskType;
        }

        public LocalDateTime getSubmittedAt() {
            return submittedAt;
        }

        public int getQuotaChange() {
            return quotaChange;
        }
    }

    public static class QuotaTaskResult {
        private final String taskId;
        private final String scheduleId;
        private final String appointmentId;
        private final boolean success;
        private final String message;
        private final LocalDateTime completedAt;
        private final int previousQuota;
        private final int newQuota;

        public QuotaTaskResult(String taskId, String scheduleId, String appointmentId, 
                              boolean success, String message, 
                              int previousQuota, int newQuota) {
            this.taskId = taskId;
            this.scheduleId = scheduleId;
            this.appointmentId = appointmentId;
            this.success = success;
            this.message = message;
            this.completedAt = LocalDateTime.now();
            this.previousQuota = previousQuota;
            this.newQuota = newQuota;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getScheduleId() {
            return scheduleId;
        }

        public String getAppointmentId() {
            return appointmentId;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public LocalDateTime getCompletedAt() {
            return completedAt;
        }

        public int getPreviousQuota() {
            return previousQuota;
        }

        public int getNewQuota() {
            return newQuota;
        }
    }

    public AsyncQuotaService(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
        this.executorService = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("Quota-Worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    public String submitQuotaRestore(String scheduleId, String appointmentId) {
        String taskId = generateTaskId();
        QuotaTask task = new QuotaTask(taskId, scheduleId, appointmentId, "RESTORE", 1);
        
        taskQueue.offer(task);
        taskCounter.incrementAndGet();
        
        log.info("提交名额恢复任务 - 任务ID: {}, 排班ID: {}, 挂号ID: {}", taskId, scheduleId, appointmentId);
        
        executorService.submit(() -> processTask(task));
        
        return taskId;
    }

    public String submitQuotaRelease(String scheduleId, String appointmentId) {
        String taskId = generateTaskId();
        QuotaTask task = new QuotaTask(taskId, scheduleId, appointmentId, "RELEASE", 1);
        
        taskQueue.offer(task);
        taskCounter.incrementAndGet();
        
        log.info("提交名额释放任务 - 任务ID: {}, 排班ID: {}, 挂号ID: {}", taskId, scheduleId, appointmentId);
        
        executorService.submit(() -> processTask(task));
        
        return taskId;
    }

    private void processTask(QuotaTask task) {
        log.info("Worker开始处理任务 - 任务ID: {}, 类型: {}", task.getTaskId(), task.getTaskType());
        
        try {
            boolean success;
            String message;
            int previousQuota = -1;
            int newQuota = -1;

            if ("RESTORE".equals(task.getTaskType())) {
                success = scheduleService.increaseAvailable(task.getScheduleId());
                message = success ? "名额恢复成功" : "名额恢复失败";
                successCounter.incrementAndGet();
            } else if ("RELEASE".equals(task.getTaskType())) {
                success = scheduleService.increaseAvailable(task.getScheduleId());
                message = success ? "名额释放成功" : "名额释放失败";
                successCounter.incrementAndGet();
            } else {
                success = false;
                message = "未知任务类型: " + task.getTaskType();
                failureCounter.incrementAndGet();
            }

            QuotaTaskResult result = new QuotaTaskResult(
                    task.getTaskId(), 
                    task.getScheduleId(), 
                    task.getAppointmentId(), 
                    success, 
                    message, 
                    previousQuota, 
                    newQuota
            );
            
            completedTasks.add(result);
            log.info("Worker完成任务 - 任务ID: {}, 类型: {}, 结果: {}", 
                    task.getTaskId(), task.getTaskType(), success ? "成功" : "失败");

        } catch (Exception e) {
            failureCounter.incrementAndGet();
            QuotaTaskResult result = new QuotaTaskResult(
                    task.getTaskId(), 
                    task.getScheduleId(), 
                    task.getAppointmentId(), 
                    false, 
                    "执行异常: " + e.getMessage(), 
                    -1, 
                    -1
            );
            completedTasks.add(result);
            log.error("Worker处理任务失败 - 任务ID: {}, 错误: {}", task.getTaskId(), e.getMessage(), e);
        }
    }

    public QuotaTaskResult getTaskResult(String taskId) {
        return completedTasks.stream()
                .filter(r -> r.getTaskId().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    public List<QuotaTaskResult> getAllCompletedTasks() {
        return new ArrayList<>(completedTasks);
    }

    public int getTotalTasks() {
        return taskCounter.get();
    }

    public int getSuccessfulTasks() {
        return successCounter.get();
    }

    public int getFailedTasks() {
        return failureCounter.get();
    }

    public int getPendingTasks() {
        return taskQueue.size();
    }

    public void waitForTaskCompletion(String taskId, long timeoutMs) throws InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (getTaskResult(taskId) != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new TimeoutException("等待任务完成超时: " + taskId);
    }

    public void waitForAllTasks(long timeoutMs) throws InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (getPendingTasks() == 0 && completedTasks.size() >= taskCounter.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new TimeoutException("等待所有任务完成超时");
    }

    public void resetCounters() {
        taskCounter.set(0);
        successCounter.set(0);
        failureCounter.set(0);
        taskQueue.clear();
        completedTasks.clear();
        log.info("异步配额服务计数器已重置");
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("异步配额服务已关闭");
    }

    private String generateTaskId() {
        return "QUOTA_TASK_" + System.currentTimeMillis() + "_" + taskCounter.incrementAndGet();
    }
}
