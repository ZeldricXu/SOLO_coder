package com.finance.worker;

import com.finance.entity.CategoryMatchTask;
import com.finance.service.CategoryMatchTaskService;
import com.finance.service.RedisQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryMatchWorker {

    private final CategoryMatchTaskService taskService;
    private final RedisQueueService redisQueueService;
    private final ObjectMapper objectMapper;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int workerCount = 3;

    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(workerCount);
        running.set(true);

        log.info("分类匹配Worker初始化完成，Worker数量: {}", workerCount);

        taskService.recoverPendingTasks();

        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            executorService.submit(() -> runWorker(workerId));
        }
    }

    private void runWorker(int workerId) {
        log.info("Worker {} 启动", workerId);

        while (running.get()) {
            try {
                String taskJson = redisQueueService.popFromQueue(RedisQueueService.DEFAULT_QUEUE_KEY, 10);

                if (taskJson != null) {
                    log.debug("Worker {} 收到任务", workerId);
                    processTask(taskJson);
                }

            } catch (Exception e) {
                log.error("Worker {} 处理任务异常", workerId, e);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Worker {} 停止", workerId);
    }

    private void processTask(String taskJson) {
        try {
            CategoryMatchTask task = objectMapper.readValue(taskJson, CategoryMatchTask.class);

            log.info("开始处理分类匹配任务: taskId={}, recordId={}", task.getTaskId(), task.getRecordId());

            boolean success = taskService.processTask(task);

            if (success) {
                log.info("任务处理成功: taskId={}", task.getTaskId());
            } else {
                log.warn("任务处理失败或需要重试: taskId={}", task.getTaskId());
            }

        } catch (Exception e) {
            log.error("解析任务失败", e);
            redisQueueService.nackMessage(taskJson, RedisQueueService.DEFAULT_QUEUE_KEY);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void checkAndRecoverTasks() {
        try {
            redisQueueService.checkRedisConnection();

            long pendingCount = taskService.countPendingTasks();
            long failedCount = taskService.countFailedTasks();

            if (pendingCount > 0 || failedCount > 0) {
                log.info("定时检查任务状态: 待处理={}, 失败={}", pendingCount, failedCount);
                taskService.recoverPendingTasks();
            }

            long dlqSize = redisQueueService.getDlqSize();
            if (dlqSize > 0) {
                log.warn("死信队列存在未处理消息: count={}", dlqSize);
            }

        } catch (Exception e) {
            log.error("定时任务检查异常", e);
        }
    }

    @Scheduled(fixedRate = 300000)
    public void recoverFromDlq() {
        try {
            long dlqSize = redisQueueService.getDlqSize();
            if (dlqSize > 0) {
                log.info("尝试从死信队列恢复消息: count={}", dlqSize);
                redisQueueService.recoverFromDlq();
            }
        } catch (Exception e) {
            log.error("从死信队列恢复失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭分类匹配Worker...");
        running.set(false);

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("分类匹配Worker已关闭");
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getPendingTaskCount() {
        return taskService.countPendingTasks();
    }

    public long getCompletedTaskCount() {
        return taskService.countCompletedTasks();
    }

    public long getFailedTaskCount() {
        return taskService.countFailedTasks();
    }
}
