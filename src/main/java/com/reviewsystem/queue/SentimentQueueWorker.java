package com.reviewsystem.queue;

import com.reviewsystem.config.QueueConfig;
import com.reviewsystem.service.SentimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class SentimentQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(SentimentQueueWorker.class);

    @Resource
    private RedisQueueService redisQueueService;

    @Resource
    private QueueConfig queueConfig;

    @Resource
    private SentimentService sentimentService;

    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        if (queueConfig.getSentiment().isWorkerEnabled()) {
            running = true;
            log.info("情感分析队列Worker已启动，队列: {}, 间隔: {}ms",
                    queueConfig.getSentiment().getName(),
                    queueConfig.getSentiment().getWorkerInterval());
        }
    }

    @Scheduled(fixedDelayString = "${review.queue.sentiment.worker-interval:1000}")
    public void processQueue() {
        if (!running || !queueConfig.getSentiment().isWorkerEnabled()) {
            return;
        }

        String queueName = queueConfig.getSentiment().getName();
        int batchSize = queueConfig.getSentiment().getBatchSize();
        int maxRetry = queueConfig.getSentiment().getRetryCount();

        List<SentimentTask> tasks = redisQueueService.popTasksBatch(queueName, batchSize, SentimentTask.class);

        for (SentimentTask task : tasks) {
            processTask(task, queueName, maxRetry);
        }
    }

    private void processTask(SentimentTask task, String queueName, int maxRetry) {
        try {
            task.setStatus(SentimentTask.TaskStatus.PROCESSING);
            log.debug("开始处理情感分析任务: {}", task.getTaskId());

            sentimentService.executeSentimentAnalysis(task);

            task.setStatus(SentimentTask.TaskStatus.COMPLETED);
            task.setProcessedAt(LocalDateTime.now());
            log.info("情感分析任务处理完成: {}", task.getTaskId());

        } catch (Exception e) {
            log.error("情感分析任务处理失败: {}, 重试次数: {}", task.getTaskId(), task.getRetryCount(), e);

            task.setRetryCount(task.getRetryCount() + 1);
            task.setErrorMessage(e.getMessage());

            if (task.getRetryCount() < maxRetry) {
                redisQueueService.pushTask(queueName, task);
                log.warn("情感分析任务重新入队: {}, 第{}次重试", task.getTaskId(), task.getRetryCount());
            } else {
                task.setStatus(SentimentTask.TaskStatus.FAILED);
                redisQueueService.moveToDeadQueue(queueName, task, e.getMessage());
                log.error("情感分析任务移入死信队列: {}", task.getTaskId());
            }
        }
    }

    public void stop() {
        running = false;
        log.info("情感分析队列Worker已停止");
    }

    public long getPendingCount() {
        return redisQueueService.getQueueSize(queueConfig.getSentiment().getName());
    }

    public long getProcessingCount() {
        return redisQueueService.getQueueSize(queueConfig.getSentiment().getName() + ":processing");
    }

    public long getDeadCount() {
        return redisQueueService.getQueueSize(queueConfig.getSentiment().getName() + ":dead");
    }
}
