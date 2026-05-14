package com.library.librarymgmt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.librarymgmt.config.LibraryConfig;
import com.library.librarymgmt.dto.NotificationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class RedisNotificationQueueService {

    private static final Logger logger = LoggerFactory.getLogger(RedisNotificationQueueService.class);

    private final StringRedisTemplate redisTemplate;
    private final LibraryConfig libraryConfig;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final ReserveService reserveService;
    private final HistoryService historyService;

    private ExecutorService workerExecutor;
    private volatile boolean running = false;

    public RedisNotificationQueueService(StringRedisTemplate redisTemplate,
                                          LibraryConfig libraryConfig,
                                          NotificationService notificationService,
                                          ObjectMapper objectMapper,
                                          ReserveService reserveService,
                                          HistoryService historyService) {
        this.redisTemplate = redisTemplate;
        this.libraryConfig = libraryConfig;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.reserveService = reserveService;
        this.historyService = historyService;
    }

    @PostConstruct
    public void init() {
        this.workerExecutor = Executors.newFixedThreadPool(
                libraryConfig.getNotification().getRedisQueue().getMaxWorkers()
        );
        this.running = true;
        startWorkers();
    }

    @PreDestroy
    public void cleanup() {
        this.running = false;
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startWorkers() {
        for (int i = 0; i < libraryConfig.getNotification().getRedisQueue().getMaxWorkers(); i++) {
            workerExecutor.submit(this::workerLoop);
        }
        logger.info("启动了 {} 个通知处理Worker", libraryConfig.getNotification().getRedisQueue().getMaxWorkers());
    }

    private void workerLoop() {
        while (running) {
            try {
                String taskJson = redisTemplate.opsForList().rightPop(
                        libraryConfig.getNotification().getRedisQueue().getQueueKey(),
                        1,
                        TimeUnit.SECONDS
                );

                if (taskJson != null) {
                    processTask(taskJson);
                }
            } catch (Exception e) {
                logger.error("Worker处理任务时发生错误: {}", e.getMessage(), e);
                try {
                    Thread.sleep(libraryConfig.getNotification().getRedisQueue().getPollIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processTask(String taskJson) {
        try {
            NotificationTask task = objectMapper.readValue(taskJson, NotificationTask.class);
            logger.info("处理通知任务: reserveId={}, bookId={}, readerId={}",
                    task.getReserveId(), task.getBookId(), task.getReaderId());

            boolean success = sendNotification(task);

            if (success) {
                handleSuccess(task);
            } else {
                handleFailure(task);
            }
        } catch (Exception e) {
            logger.error("解析或处理通知任务失败: {}", e.getMessage(), e);
        }
    }

    private boolean sendNotification(NotificationTask task) {
        try {
            return notificationService.sendReservationNotificationSync(
                    task.getReserveId(),
                    task.getBookId(),
                    task.getReaderId(),
                    libraryConfig.getNotification().getMaxRetries()
            );
        } catch (Exception e) {
            logger.error("发送通知时发生异常: {}", e.getMessage());
            return false;
        }
    }

    private void handleSuccess(NotificationTask task) {
        try {
            reserveService.updateReserveStatus(task.getReserveId(), "notified");
            historyService.log(
                    "reserve_notification",
                    task.getReserveId(),
                    task.getBookId(),
                    task.getReaderId(),
                    "预约通知发送成功"
            );
            logger.info("通知发送成功: reserveId={}", task.getReserveId());
        } catch (Exception e) {
            logger.error("更新预约状态失败: {}", e.getMessage());
        }
    }

    private void handleFailure(NotificationTask task) {
        int maxRetries = libraryConfig.getNotification().getMaxRetries();
        task.setRetryCount(task.getRetryCount() + 1);
        task.setLastRetryAt(Instant.now());

        if (task.getRetryCount() < maxRetries) {
            try {
                task.setStatus("retry");
                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().leftPush(
                        libraryConfig.getNotification().getRedisQueue().getQueueKey(),
                        taskJson
                );
                logger.info("通知发送失败，进行第 {} 次重试: reserveId={}",
                        task.getRetryCount(), task.getReserveId());
            } catch (JsonProcessingException e) {
                logger.error("序列化重试任务失败: {}", e.getMessage());
            }
        } else {
            try {
                historyService.log(
                        "reserve_notification_failed",
                        task.getReserveId(),
                        task.getBookId(),
                        task.getReaderId(),
                        "预约通知发送失败，已达到最大重试次数: " + maxRetries
                );
                logger.error("通知发送达到最大重试次数，放弃: reserveId={}", task.getReserveId());
            } catch (Exception e) {
                logger.error("记录失败日志失败: {}", e.getMessage());
            }
        }
    }

    public boolean enqueueNotification(String reserveId, String bookId, String readerId) {
        try {
            NotificationTask task = new NotificationTask(reserveId, bookId, readerId);
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().leftPush(
                    libraryConfig.getNotification().getRedisQueue().getQueueKey(),
                    taskJson
            );
            logger.info("通知任务已入队: taskId={}, reserveId={}", task.getTaskId(), reserveId);
            return true;
        } catch (JsonProcessingException e) {
            logger.error("序列化通知任务失败: {}", e.getMessage());
            return false;
        }
    }

    public List<NotificationTask> getPendingTasks() {
        List<NotificationTask> tasks = new ArrayList<>();
        try {
            List<String> taskJsons = redisTemplate.opsForList().range(
                    libraryConfig.getNotification().getRedisQueue().getQueueKey(),
                    0,
                    -1
            );
            if (taskJsons != null) {
                for (String taskJson : taskJsons) {
                    tasks.add(objectMapper.readValue(taskJson, NotificationTask.class));
                }
            }
        } catch (Exception e) {
            logger.error("获取待处理任务失败: {}", e.getMessage());
        }
        return tasks;
    }

    public long getPendingTaskCount() {
        Long count = redisTemplate.opsForList().size(
                libraryConfig.getNotification().getRedisQueue().getQueueKey()
        );
        return count != null ? count : 0;
    }
}
