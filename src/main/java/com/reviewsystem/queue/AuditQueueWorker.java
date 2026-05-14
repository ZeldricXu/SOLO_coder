package com.reviewsystem.queue;

import com.reviewsystem.config.QueueConfig;
import com.reviewsystem.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AuditQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditQueueWorker.class);

    @Resource
    private RedisQueueService redisQueueService;

    @Resource
    private QueueConfig queueConfig;

    @Resource
    private AuditService auditService;

    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        if (queueConfig.getAudit().isWorkerEnabled()) {
            running = true;
            log.info("审核队列Worker已启动，队列: {}, 间隔: {}ms",
                    queueConfig.getAudit().getName(),
                    queueConfig.getAudit().getWorkerInterval());
        }
    }

    @Scheduled(fixedDelayString = "${review.queue.audit.worker-interval:1000}")
    public void processQueue() {
        if (!running || !queueConfig.getAudit().isWorkerEnabled()) {
            return;
        }

        String queueName = queueConfig.getAudit().getName();
        int batchSize = queueConfig.getAudit().getBatchSize();
        int maxRetry = queueConfig.getAudit().getRetryCount();

        List<AuditTask> tasks = redisQueueService.popTasksBatch(queueName, batchSize, AuditTask.class);

        for (AuditTask task : tasks) {
            processTask(task, queueName, maxRetry);
        }
    }

    private void processTask(AuditTask task, String queueName, int maxRetry) {
        try {
            task.setStatus(AuditTask.TaskStatus.PROCESSING);
            log.debug("开始处理审核任务: {}", task.getTaskId());

            auditService.executeAudit(task);

            task.setStatus(AuditTask.TaskStatus.COMPLETED);
            task.setProcessedAt(LocalDateTime.now());
            log.info("审核任务处理完成: {}", task.getTaskId());

        } catch (Exception e) {
            log.error("审核任务处理失败: {}, 重试次数: {}", task.getTaskId(), task.getRetryCount(), e);

            task.setRetryCount(task.getRetryCount() + 1);
            task.setErrorMessage(e.getMessage());

            if (task.getRetryCount() < maxRetry) {
                redisQueueService.pushTask(queueName, task);
                log.warn("审核任务重新入队: {}, 第{}次重试", task.getTaskId(), task.getRetryCount());
            } else {
                task.setStatus(AuditTask.TaskStatus.FAILED);
                redisQueueService.moveToDeadQueue(queueName, task, e.getMessage());
                log.error("审核任务移入死信队列: {}", task.getTaskId());
            }
        }
    }

    public void stop() {
        running = false;
        log.info("审核队列Worker已停止");
    }

    public long getPendingCount() {
        return redisQueueService.getQueueSize(queueConfig.getAudit().getName());
    }

    public long getProcessingCount() {
        return redisQueueService.getQueueSize(queueConfig.getAudit().getName() + ":processing");
    }

    public long getDeadCount() {
        return redisQueueService.getQueueSize(queueConfig.getAudit().getName() + ":dead");
    }
}
