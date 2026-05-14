package com.movie.scheduler;

import com.movie.dto.ScheduleCreateRequest;
import com.movie.entity.Schedule;
import com.movie.service.ScheduleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class ScheduleAsyncWorker {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @Autowired
    private ScheduleService scheduleService;

    private final ExecutorService workerPool = Executors.newFixedThreadPool(4);
    private final Queue<ScheduleTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public static class ScheduleTask {
        private final String taskId;
        private final ScheduleCreateRequest request;
        private final Consumer<Schedule> onComplete;
        private final Consumer<Exception> onError;
        private final long createdAt;

        public ScheduleTask(String taskId, ScheduleCreateRequest request,
                           Consumer<Schedule> onComplete, Consumer<Exception> onError) {
            this.taskId = taskId;
            this.request = request;
            this.onComplete = onComplete;
            this.onError = onError;
            this.createdAt = System.currentTimeMillis();
        }

        public String getTaskId() {
            return taskId;
        }

        public ScheduleCreateRequest getRequest() {
            return request;
        }

        public Consumer<Schedule> getOnComplete() {
            return onComplete;
        }

        public Consumer<Exception> getOnError() {
            return onError;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    public static class TaskStatus {
        private final String taskId;
        private volatile String status;
        private volatile Schedule result;
        private volatile Exception error;
        private volatile long startedAt;
        private volatile long completedAt;

        public TaskStatus(String taskId) {
            this.taskId = taskId;
            this.status = STATUS_PENDING;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Schedule getResult() {
            return result;
        }

        public void setResult(Schedule result) {
            this.result = result;
        }

        public Exception getError() {
            return error;
        }

        public void setError(Exception error) {
            this.error = error;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(long startedAt) {
            this.startedAt = startedAt;
        }

        public long getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(long completedAt) {
            this.completedAt = completedAt;
        }

        public boolean isDone() {
            return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
        }
    }

    @PostConstruct
    public void init() {
        startWorkerThreads();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(60, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void startWorkerThreads() {
        for (int i = 0; i < 4; i++) {
            workerPool.submit(this::processTaskQueue);
        }
    }

    private void processTaskQueue() {
        while (running) {
            ScheduleTask task = taskQueue.poll();
            if (task == null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            processTask(task);
        }
    }

    private void processTask(ScheduleTask task) {
        TaskStatus taskStatus = taskStatusMap.get(task.getTaskId());
        if (taskStatus == null) {
            return;
        }

        try {
            taskStatus.setStatus(STATUS_PROCESSING);
            taskStatus.setStartedAt(System.currentTimeMillis());

            Schedule schedule = scheduleService.createSchedule(task.getRequest());

            taskStatus.setResult(schedule);
            taskStatus.setStatus(STATUS_COMPLETED);
            taskStatus.setCompletedAt(System.currentTimeMillis());

            if (task.getOnComplete() != null) {
                task.getOnComplete().accept(schedule);
            }
        } catch (Exception e) {
            taskStatus.setError(e);
            taskStatus.setStatus(STATUS_FAILED);
            taskStatus.setCompletedAt(System.currentTimeMillis());

            if (task.getOnError() != null) {
                task.getOnError().accept(e);
            }
        }
    }

    public String submitTask(String taskId, ScheduleCreateRequest request,
                            Consumer<Schedule> onComplete, Consumer<Exception> onError) {
        TaskStatus taskStatus = new TaskStatus(taskId);
        taskStatusMap.put(taskId, taskStatus);

        ScheduleTask task = new ScheduleTask(taskId, request, onComplete, onError);
        taskQueue.offer(task);

        return taskId;
    }

    public TaskStatus getTaskStatus(String taskId) {
        return taskStatusMap.get(taskId);
    }

    public String getTaskStatusString(String taskId) {
        TaskStatus status = taskStatusMap.get(taskId);
        if (status == null) {
            return "unknown";
        }
        return status.getStatus();
    }

    public int getPendingTaskCount() {
        return (int) taskStatusMap.values().stream()
                .filter(s -> STATUS_PENDING.equals(s.getStatus()))
                .count();
    }

    public int getProcessingTaskCount() {
        return (int) taskStatusMap.values().stream()
                .filter(s -> STATUS_PROCESSING.equals(s.getStatus()))
                .count();
    }

    public int getCompletedTaskCount() {
        return (int) taskStatusMap.values().stream()
                .filter(s -> STATUS_COMPLETED.equals(s.getStatus()))
                .count();
    }

    public int getFailedTaskCount() {
        return (int) taskStatusMap.values().stream()
                .filter(s -> STATUS_FAILED.equals(s.getStatus()))
                .count();
    }

    public void clearCompletedTasks() {
        taskStatusMap.entrySet().removeIf(entry -> 
                STATUS_COMPLETED.equals(entry.getValue().getStatus()) ||
                STATUS_FAILED.equals(entry.getValue().getStatus()));
    }
}
