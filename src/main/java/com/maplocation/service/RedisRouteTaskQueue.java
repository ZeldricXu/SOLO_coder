package com.maplocation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplocation.model.Coordinates;
import com.maplocation.model.RouteTask;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisRouteTaskQueue {

    private static final String QUEUE_KEY = "route_task_queue";
    private static final String PROCESSING_SET_KEY = "route_task_processing";
    private static final String TASK_PREFIX = "route_task:";
    private static final String STATUS_PREFIX = "route_status:";
    private static final long TASK_TTL_HOURS = 24;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void recoverProcessingTasks() {
        Set<String> processingTasks = redisTemplate.opsForSet().members(PROCESSING_SET_KEY);
        if (processingTasks != null && !processingTasks.isEmpty()) {
            for (String taskId : processingTasks) {
                try {
                    RouteTask task = getTask(taskId);
                    if (task != null) {
                        task.setStatus(RouteTask.TaskStatus.PENDING);
                        saveTask(task);
                        redisTemplate.opsForSet().remove(PROCESSING_SET_KEY, taskId);
                        log.info("Recovered task {} from processing to pending", taskId);
                    }
                } catch (Exception e) {
                    log.error("Failed to recover task {}", taskId, e);
                }
            }
        }
    }

    public String submitTask(String routeType, List<Coordinates> waypoints, String routeId) {
        String taskId = "route_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);

        RouteTask task = RouteTask.builder()
                .taskId(taskId)
                .routeType(routeType)
                .waypoints(waypoints)
                .routeId(routeId)
                .status(RouteTask.TaskStatus.PENDING)
                .submittedAt(Instant.now())
                .build();

        saveTask(task);
        redisTemplate.opsForList().leftPush(QUEUE_KEY, taskId);
        updateTaskStatus(taskId, RouteTask.TaskStatus.PENDING);

        log.info("Submitted route task: {}", taskId);
        return taskId;
    }

    public Optional<String> pollTask() {
        String taskId = redisTemplate.opsForList().rightPop(QUEUE_KEY);
        if (taskId == null) {
            return Optional.empty();
        }

        redisTemplate.opsForSet().add(PROCESSING_SET_KEY, taskId);

        try {
            RouteTask task = getTask(taskId);
            if (task != null) {
                task.setStatus(RouteTask.TaskStatus.PROCESSING);
                task.setStartedAt(Instant.now());
                saveTask(task);
                updateTaskStatus(taskId, RouteTask.TaskStatus.PROCESSING);
            }
        } catch (Exception e) {
            log.warn("Failed to update task status for {}: {}", taskId, e.getMessage());
        }

        return Optional.of(taskId);
    }

    public RouteTask getTask(String taskId) {
        String taskKey = TASK_PREFIX + taskId;
        String taskJson = redisTemplate.opsForValue().get(taskKey);
        if (taskJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(taskJson, RouteTask.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize route task: {}", taskId, e);
            return null;
        }
    }

    public void markCompleted(String taskId) {
        RouteTask task = getTask(taskId);
        if (task != null) {
            task.setStatus(RouteTask.TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            saveTask(task);
            updateTaskStatus(taskId, RouteTask.TaskStatus.COMPLETED);
            redisTemplate.opsForSet().remove(PROCESSING_SET_KEY, taskId);
            log.info("Completed route task: {}", taskId);
        }
    }

    public void markFailed(String taskId, String errorMessage) {
        RouteTask task = getTask(taskId);
        if (task != null) {
            task.setStatus(RouteTask.TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            task.setErrorMessage(errorMessage);
            saveTask(task);
            updateTaskStatus(taskId, RouteTask.TaskStatus.FAILED);
            redisTemplate.opsForSet().remove(PROCESSING_SET_KEY, taskId);
            log.error("Failed route task: {}, error: {}", taskId, errorMessage);
        }
    }

    public RouteTask.TaskStatus getTaskStatus(String taskId) {
        String statusKey = STATUS_PREFIX + taskId;
        String statusStr = redisTemplate.opsForValue().get(statusKey);
        if (statusStr == null) {
            return null;
        }
        try {
            return RouteTask.TaskStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public long getPendingTaskCount() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    public long getProcessingTaskCount() {
        Long size = redisTemplate.opsForSet().size(PROCESSING_SET_KEY);
        return size != null ? size : 0;
    }

    private void saveTask(RouteTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            String taskKey = TASK_PREFIX + task.getTaskId();
            redisTemplate.opsForValue().set(taskKey, taskJson, TASK_TTL_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize route task: {}", task.getTaskId(), e);
        }
    }

    private void updateTaskStatus(String taskId, RouteTask.TaskStatus status) {
        String statusKey = STATUS_PREFIX + taskId;
        redisTemplate.opsForValue().set(statusKey, status.name(), TASK_TTL_HOURS, TimeUnit.HOURS);
    }

    public void clearAllTasks() {
        Set<String> allKeys = redisTemplate.keys("route_*");
        if (allKeys != null && !allKeys.isEmpty()) {
            redisTemplate.delete(allKeys);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredTasks() {
        log.debug("Queue stats - pending: {}, processing: {}",
                getPendingTaskCount(), getProcessingTaskCount());
    }
}
