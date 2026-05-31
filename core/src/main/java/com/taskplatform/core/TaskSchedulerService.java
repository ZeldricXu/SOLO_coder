package com.taskplatform.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.config.ConfigService;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.persistence.mapper.TaskMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerService {

    private static final int DEFAULT_SCHEDULER_THREADS = 10;
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int DEFAULT_STUCK_TIMEOUT_MINUTES = 30;
    private static final long AWAIT_TERMINATION_SECONDS = 30;

    private final TaskMapper taskMapper;
    private final TaskExecutorService taskExecutorService;
    private final ConfigService configService;

    private final ExecutorService schedulerExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Queue<Task> taskQueue = new ConcurrentLinkedQueue<>();

    public TaskSchedulerService(TaskMapper taskMapper,
                                 TaskExecutorService taskExecutorService,
                                 ConfigService configService) {
        this.taskMapper = taskMapper;
        this.taskExecutorService = taskExecutorService;
        this.configService = configService;
        this.schedulerExecutor = new ThreadPoolExecutor(
                DEFAULT_SCHEDULER_THREADS,
                DEFAULT_SCHEDULER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .withNameFormat("task-scheduler-%d")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Scheduled(fixedDelayString = "${task.scheduler.interval:5000}")
    public void scheduleTasks() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            int batchSize = configService.getInt("task", "scheduler.batch.size", DEFAULT_BATCH_SIZE);
            List<Task> tasks = fetchQueuedTasks(batchSize);

            if (tasks.isEmpty()) {
                return;
            }

            taskQueue.addAll(tasks);

            Task task;
            while ((task = taskQueue.poll()) != null) {
                if (shouldSchedule(task)) {
                    schedulerExecutor.submit(() -> executeTaskSafely(task));
                }
            }
        } catch (Exception e) {
            log.error("Task scheduling failed", e);
        } finally {
            running.set(false);
        }
    }

    private List<Task> fetchQueuedTasks(int batchSize) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .in(Task::getStatus, TaskStatus.QUEUED, TaskStatus.PENDING)
                        .apply("ORDER BY CASE priority " +
                                "WHEN '" + TaskPriority.CRITICAL + "' THEN 0 " +
                                "WHEN '" + TaskPriority.HIGH + "' THEN 1 " +
                                "WHEN '" + TaskPriority.NORMAL + "' THEN 2 " +
                                "WHEN '" + TaskPriority.LOW + "' THEN 3 END, " +
                                "created_at ASC LIMIT " + batchSize)
        );
    }

    private void executeTaskSafely(Task task) {
        try {
            taskExecutorService.executeTask(task.getTaskId());
        } catch (Exception e) {
            log.error("Scheduled task execution failed: {}", task.getTaskId(), e);
        }
    }

    private boolean shouldSchedule(Task task) {
        if (task.getScheduledAt() == null) {
            return true;
        }
        return task.getScheduledAt().isBefore(LocalDateTime.now());
    }

    @Scheduled(fixedDelayString = "${task.cleanup.interval:60000}")
    public void cleanupStuckTasks() {
        int timeoutMinutes = configService.getInt("task", "stuck.timeout.minutes", DEFAULT_STUCK_TIMEOUT_MINUTES);
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);

        List<Task> stuckTasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getStatus, TaskStatus.RUNNING)
                        .lt(Task::getUpdatedAt, threshold)
        );

        if (stuckTasks.isEmpty()) {
            return;
        }

        log.info("Found {} stuck tasks to cleanup", stuckTasks.size());

        for (Task task : stuckTasks) {
            try {
                markTaskAsStuck(task, timeoutMinutes);
            } catch (Exception e) {
                log.error("Failed to mark task as stuck: {}", task.getTaskId(), e);
            }
        }
    }

    private void markTaskAsStuck(Task task, int timeoutMinutes) {
        log.warn("Marking stuck task: {}", task.getTaskId());
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage("Task stuck - no update for " + timeoutMinutes + " minutes");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    public void submitToQueue(Task task) {
        taskQueue.offer(task);
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    @PreDestroy
    public void shutdown() {
        schedulerExecutor.shutdown();
        try {
            if (!schedulerExecutor.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                schedulerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            schedulerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static class ThreadFactoryBuilder {
        private String nameFormat;
        private int counter = 0;

        ThreadFactoryBuilder withNameFormat(String nameFormat) {
            this.nameFormat = nameFormat;
            return this;
        }

        ThreadFactory build() {
            return r -> {
                Thread thread = new Thread(r);
                thread.setName(String.format(nameFormat, counter++));
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
