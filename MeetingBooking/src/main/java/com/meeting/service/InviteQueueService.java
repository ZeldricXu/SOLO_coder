package com.meeting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meeting.config.InviteConfig;
import com.meeting.entity.InviteTask;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class InviteQueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final InviteConfig inviteConfig;
    private final AttendeeService attendeeService;
    private final HistoryService historyService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutorService workerExecutor;
    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        objectMapper.registerModule(new JavaTimeModule());
        if (inviteConfig.getWorker().isEnabled()) {
            startWorker();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void startWorker() {
        if (running) {
            log.warn("InviteQueueWorker已经在运行");
            return;
        }
        running = true;
        workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "invite-queue-worker");
            thread.setDaemon(true);
            return thread;
        });
        workerExecutor.submit(this::workerLoop);
        log.info("InviteQueueWorker已启动");
    }

    private void workerLoop() {
        while (running) {
            try {
                List<InviteTask> tasks = pollTasks(inviteConfig.getWorker().getBatchSize());
                if (!tasks.isEmpty()) {
                    log.info("从队列获取到{}个邀请任务", tasks.size());
                    for (InviteTask task : tasks) {
                        processTask(task);
                    }
                } else {
                    Thread.sleep(inviteConfig.getWorker().getPollIntervalMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("InviteQueueWorker被中断");
                break;
            } catch (Exception e) {
                log.error("InviteQueueWorker处理异常", e);
                try {
                    Thread.sleep(inviteConfig.getWorker().getPollIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("InviteQueueWorker已停止");
    }

    public boolean submitTask(InviteTask task) {
        try {
            if (task.getTaskId() == null || task.getTaskId().isEmpty()) {
                task.setTaskId(IdGenerator.generateHistoryId());
            }
            if (task.getStatus() == null) {
                task.setStatus(InviteTask.STATUS_PENDING);
            }
            if (task.getCreatedAt() == null) {
                task.setCreatedAt(LocalDateTime.now());
            }
            if (task.getMaxRetryCount() == 0) {
                task.setMaxRetryCount(inviteConfig.getRetry().getMaxRetryCount());
            }

            String taskJson = objectMapper.writeValueAsString(task);
            Long result = redisTemplate.opsForList().rightPush(inviteConfig.getQueueName(), taskJson);

            log.info("邀请任务已提交到队列: taskId={}, meetingId={}, userId={}",
                    task.getTaskId(), task.getMeetingId(), task.getUserId());

            return result != null && result > 0;
        } catch (JsonProcessingException e) {
            log.error("序列化邀请任务失败", e);
            return false;
        }
    }

    public List<InviteTask> pollTasks(int count) {
        List<InviteTask> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object taskJson = redisTemplate.opsForList().leftPop(inviteConfig.getQueueName());
            if (taskJson == null) {
                break;
            }
            try {
                InviteTask task = objectMapper.readValue(taskJson.toString(), InviteTask.class);
                tasks.add(task);
            } catch (Exception e) {
                log.error("反序列化邀请任务失败: {}", taskJson, e);
            }
        }
        return tasks;
    }

    private void processTask(InviteTask task) {
        log.info("开始处理邀请任务: taskId={}, meetingId={}, userId={}",
                task.getTaskId(), task.getMeetingId(), task.getUserId());

        task.setStatus(InviteTask.STATUS_PROCESSING);
        task.setLastRetryAt(LocalDateTime.now());

        try {
            boolean success = attendeeService.inviteSingleAttendee(
                    task.getMeetingId(),
                    task.getUserId(),
                    task.getUserName(),
                    task.getUserEmail());

            if (success) {
                task.setStatus(InviteTask.STATUS_SUCCESS);
                log.info("邀请任务处理成功: taskId={}, meetingId={}, userId={}",
                        task.getTaskId(), task.getMeetingId(), task.getUserId());
                return;
            }

            handleRetry(task, "邀请处理失败");

        } catch (Exception e) {
            log.error("邀请任务处理异常: taskId={}", task.getTaskId(), e);
            handleRetry(task, e.getMessage());
        }
    }

    private void handleRetry(InviteTask task, String errorMessage) {
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(errorMessage);

        if (task.getRetryCount() < task.getMaxRetryCount()) {
            long delayMs = inviteConfig.getRetry().getRetryDelayMs() *
                    (long) Math.pow(inviteConfig.getRetry().getBackoffMultiplier(), task.getRetryCount() - 1);

            log.warn("邀请任务准备重试: taskId={}, retryCount={}/{}, delay={}ms",
                    task.getTaskId(), task.getRetryCount(), task.getMaxRetryCount(), delayMs);

            task.setStatus(InviteTask.STATUS_PENDING);
            submitTask(task);
        } else {
            task.setStatus(InviteTask.STATUS_FAILED);
            log.error("邀请任务最终失败: taskId={}, meetingId={}, userId={}, error={}",
                    task.getTaskId(), task.getMeetingId(), task.getUserId(), errorMessage);
        }
    }

    public long getPendingTaskCount() {
        Long count = redisTemplate.opsForList().size(inviteConfig.getQueueName());
        return count != null ? count : 0;
    }

    public void clearAllTasks() {
        redisTemplate.delete(inviteConfig.getQueueName());
        log.info("已清空邀请队列");
    }

    public boolean isRedisAvailable() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            log.warn("Redis不可用: {}", e.getMessage());
            return false;
        }
    }
}
