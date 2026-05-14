package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.RiskDetectionTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class RiskDetectionQueueService {

    private static final Logger logger = LoggerFactory.getLogger(RiskDetectionQueueService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final RiskService riskService;

    private ExecutorService executorService;
    private volatile boolean running = false;

    public RiskDetectionQueueService(RedisTemplate<String, Object> redisTemplate,
                                     RiskService riskService) {
        this.redisTemplate = redisTemplate;
        this.riskService = riskService;
    }

    @PostConstruct
    public void init() {
        startWorkers();
    }

    @PreDestroy
    public void destroy() {
        stopWorkers();
    }

    public void startWorkers() {
        if (running) {
            return;
        }
        running = true;
        executorService = Executors.newFixedThreadPool(3);

        for (int i = 0; i < 3; i++) {
            executorService.submit(new RiskDetectionWorker("worker-" + i));
        }

        logger.info("风险检测Worker已启动，线程数: 3");
    }

    public void stopWorkers() {
        running = false;
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("风险检测Worker已停止");
    }

    public void submitRiskDetectionTask(Task task) {
        RiskDetectionTask detectionTask = new RiskDetectionTask(task);
        try {
            redisTemplate.opsForList().rightPush(
                    Constants.REDIS_RISK_QUEUE_KEY,
                    detectionTask
            );
            logger.info("风险检测任务已入队: taskId={}", task.getTaskId());
        } catch (Exception e) {
            logger.error("风险检测任务入队失败: taskId={}", task.getTaskId(), e);
        }
    }

    public void submitRiskDetectionTaskBatch(List<Task> tasks) {
        for (Task task : tasks) {
            submitRiskDetectionTask(task);
        }
        logger.info("批量风险检测任务已入队: 数量={}", tasks.size());
    }

    public long getQueueSize() {
        try {
            Long size = redisTemplate.opsForList().size(Constants.REDIS_RISK_QUEUE_KEY);
            return size != null ? size : 0;
        } catch (Exception e) {
            logger.error("获取队列大小失败", e);
            return 0;
        }
    }

    public List<RiskDetectionTask> peekQueue(int count) {
        List<RiskDetectionTask> tasks = new ArrayList<>();
        try {
            List<Object> objects = redisTemplate.opsForList().range(Constants.REDIS_RISK_QUEUE_KEY, 0, count - 1);
            if (objects != null) {
                for (Object obj : objects) {
                    if (obj instanceof RiskDetectionTask) {
                        tasks.add((RiskDetectionTask) obj);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("查看队列失败", e);
        }
        return tasks;
    }

    private class RiskDetectionWorker implements Runnable {
        private final String workerName;

        public RiskDetectionWorker(String workerName) {
            this.workerName = workerName;
        }

        @Override
        public void run() {
            logger.info("风险检测Worker启动: {}", workerName);

            while (running) {
                try {
                    Object taskObj = redisTemplate.opsForList().leftPop(
                            Constants.REDIS_RISK_QUEUE_KEY,
                            5,
                            TimeUnit.SECONDS
                    );

                    if (taskObj == null) {
                        continue;
                    }

                    RiskDetectionTask detectionTask;
                    if (taskObj instanceof RiskDetectionTask) {
                        detectionTask = (RiskDetectionTask) taskObj;
                    } else {
                        logger.warn("队列中对象类型不正确: {}", taskObj.getClass());
                        continue;
                    }

                    processDetectionTask(detectionTask);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Worker处理任务异常: {}", workerName, e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            logger.info("风险检测Worker停止: {}", workerName);
        }

        private void processDetectionTask(RiskDetectionTask detectionTask) {
            try {
                logger.debug("Worker处理风险检测任务: worker={}, taskId={}",
                        workerName, detectionTask.getTaskId());

                detectionTask.setStatus("processing");
                redisTemplate.opsForList().rightPush(Constants.REDIS_RISK_PROCESSING_KEY, detectionTask);

                Task task = detectionTask.toTaskModel();
                riskService.checkTaskRisk(task);

                redisTemplate.opsForList().remove(Constants.REDIS_RISK_PROCESSING_KEY, 1, detectionTask);

                logger.debug("风险检测任务处理完成: worker={}, taskId={}",
                        workerName, detectionTask.getTaskId());

            } catch (Exception e) {
                logger.error("风险检测任务处理失败: taskId={}", detectionTask.getTaskId(), e);
                detectionTask.setStatus("failed");
                try {
                    redisTemplate.opsForList().rightPush(Constants.REDIS_RISK_FAILED_KEY, detectionTask);
                } catch (Exception ex) {
                    logger.error("保存失败任务到Redis失败", ex);
                }
            }
        }
    }

    public List<RiskDetectionTask> getProcessingTasks() {
        List<RiskDetectionTask> tasks = new ArrayList<>();
        try {
            List<Object> objects = redisTemplate.opsForList().range(
                    Constants.REDIS_RISK_PROCESSING_KEY, 0, -1);
            if (objects != null) {
                for (Object obj : objects) {
                    if (obj instanceof RiskDetectionTask) {
                        tasks.add((RiskDetectionTask) obj);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("获取处理中任务失败", e);
        }
        return tasks;
    }

    public List<RiskDetectionTask> getFailedTasks() {
        List<RiskDetectionTask> tasks = new ArrayList<>();
        try {
            List<Object> objects = redisTemplate.opsForList().range(
                    Constants.REDIS_RISK_FAILED_KEY, 0, -1);
            if (objects != null) {
                for (Object obj : objects) {
                    if (obj instanceof RiskDetectionTask) {
                        tasks.add((RiskDetectionTask) obj);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("获取失败任务失败", e);
        }
        return tasks;
    }

    public void retryFailedTasks() {
        List<RiskDetectionTask> failedTasks = getFailedTasks();
        for (RiskDetectionTask task : failedTasks) {
            redisTemplate.opsForList().leftPush(Constants.REDIS_RISK_QUEUE_KEY, task);
            redisTemplate.opsForList().remove(Constants.REDIS_RISK_FAILED_KEY, 1, task);
        }
        logger.info("重试失败任务: 数量={}", failedTasks.size());
    }

    public void clearAllQueues() {
        redisTemplate.delete(Constants.REDIS_RISK_QUEUE_KEY);
        redisTemplate.delete(Constants.REDIS_RISK_PROCESSING_KEY);
        redisTemplate.delete(Constants.REDIS_RISK_FAILED_KEY);
        logger.info("所有风险检测队列已清空");
    }
}
