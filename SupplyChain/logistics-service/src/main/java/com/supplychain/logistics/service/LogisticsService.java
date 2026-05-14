package com.supplychain.logistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.LogisticsTask;
import com.supplychain.common.entity.LogisticsTracking;
import com.supplychain.common.entity.TrackingRecord;
import com.supplychain.common.enums.TrackingStatus;
import com.supplychain.common.exception.BusinessException;
import com.supplychain.common.service.RedisQueueService;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.logistics.mapper.LogisticsTrackingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsService {

    private final LogisticsTrackingMapper trackingMapper;
    private final RedisQueueService redisQueueService;

    private final List<String> statusNotifications = new ArrayList<>();
    private final Map<String, String> orderStatusUpdates = new ConcurrentHashMap<>();
    private final AtomicInteger asyncTaskCount = new AtomicInteger(0);
    private final List<String> workerExecutions = new ArrayList<>();

    @Transactional
    public LogisticsTracking createTracking(String orderId, String carrier, String trackingNumber) {
        LogisticsTracking tracking = LogisticsTracking.builder()
            .trackingId(IdGenerator.generateTrackingId())
            .orderId(orderId)
            .trackingStatus(TrackingStatus.PENDING.getCode())
            .trackingLocation("仓库待发货")
            .trackingTime(LocalDateTime.now())
            .carrier(carrier != null ? carrier : "default")
            .trackingNumber(trackingNumber != null ? trackingNumber : "TN_" + IdGenerator.generateId("num"))
            .trackingRecords(new ArrayList<>())
            .build();

        TrackingRecord record = TrackingRecord.builder()
            .status(TrackingStatus.PENDING.getCode())
            .location("仓库待发货")
            .description("订单已确认，等待发货")
            .time(LocalDateTime.now())
            .build();
        tracking.getTrackingRecords().add(record);

        trackingMapper.insert(tracking);
        log.info("创建物流追踪: orderId={}, trackingId={}", orderId, tracking.getTrackingId());
        return tracking;
    }

    public LogisticsTracking getTrackingByOrder(String orderId) {
        LambdaQueryWrapper<LogisticsTracking> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogisticsTracking::getOrderId, orderId)
               .orderByDesc(LogisticsTracking::getTrackingTime)
               .last("LIMIT 1");
        LogisticsTracking tracking = trackingMapper.selectOne(wrapper);
        if (tracking == null) {
            throw new BusinessException(404, "物流追踪记录不存在");
        }
        return tracking;
    }

    public LogisticsTracking getTracking(String trackingId) {
        LogisticsTracking tracking = trackingMapper.selectById(trackingId);
        if (tracking == null) {
            throw new BusinessException(404, "物流追踪记录不存在");
        }
        return tracking;
    }

    @Transactional
    public LogisticsTracking updateTrackingStatus(String orderId, String status, String location, String description) {
        LogisticsTracking tracking = getTrackingByOrder(orderId);

        String oldStatus = tracking.getTrackingStatus();
        tracking.setTrackingStatus(status);
        tracking.setTrackingLocation(location);
        tracking.setTrackingTime(LocalDateTime.now());

        if (tracking.getTrackingRecords() == null) {
            tracking.setTrackingRecords(new ArrayList<>());
        }

        TrackingRecord record = TrackingRecord.builder()
            .status(status)
            .location(location)
            .description(description != null ? description : getStatusDescription(status))
            .time(LocalDateTime.now())
            .build();
        tracking.getTrackingRecords().add(record);

        trackingMapper.updateById(tracking);

        if (!oldStatus.equals(status)) {
            log.info("物流状态变更: orderId={}, {} -> {}", orderId, oldStatus, status);
        }

        return tracking;
    }

    private String getStatusDescription(String status) {
        return switch (status) {
            case "pending" -> "订单等待发货";
            case "in_transit" -> "货物运输中";
            case "arrived" -> "货物已到达目的地";
            case "signed" -> "货物已签收";
            case "delayed" -> "物流延迟";
            default -> "物流状态更新";
        };
    }

    @Transactional
    public LogisticsTracking simulateTracking(String orderId) {
        LogisticsTracking tracking = getTrackingByOrder(orderId);
        String currentStatus = tracking.getTrackingStatus();
        String nextStatus = getNextStatus(currentStatus);

        if (nextStatus != null) {
            String[] locations = {"仓库待发货", "分拣中心", "运输途中", "目的地城市", "已签收"};
            int index = switch (nextStatus) {
                case "pending" -> 0;
                case "in_transit" -> 2;
                case "arrived" -> 3;
                case "signed" -> 4;
                default -> 1;
            };
            tracking = updateTrackingStatus(orderId, nextStatus, locations[index], null);
        }
        return tracking;
    }

    private String getNextStatus(String current) {
        return switch (current) {
            case "pending" -> TrackingStatus.IN_TRANSIT.getCode();
            case "in_transit" -> TrackingStatus.ARRIVED.getCode();
            case "arrived" -> TrackingStatus.SIGNED.getCode();
            default -> null;
        };
    }

    public List<LogisticsTracking> listTrackings(String orderId, String status) {
        LambdaQueryWrapper<LogisticsTracking> wrapper = new LambdaQueryWrapper<>();
        if (orderId != null && !orderId.isEmpty()) {
            wrapper.eq(LogisticsTracking::getOrderId, orderId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(LogisticsTracking::getTrackingStatus, status);
        }
        wrapper.orderByDesc(LogisticsTracking::getTrackingTime);
        return trackingMapper.selectList(wrapper);
    }

    public Map<String, Object> queryTrackingInfo(String orderId) {
        LogisticsTracking tracking = getTrackingByOrder(orderId);
        return Map.of(
            "tracking", Map.of(
                "status", tracking.getTrackingStatus(),
                "location", tracking.getTrackingLocation(),
                "tracking_id", tracking.getTrackingId(),
                "carrier", tracking.getCarrier(),
                "tracking_number", tracking.getTrackingNumber(),
                "records", tracking.getTrackingRecords() != null ? tracking.getTrackingRecords() : List.of()
            )
        );
    }

    public CompletableFuture<Map<String, Object>> submitTrackingRequest(String orderId) {
        asyncTaskCount.incrementAndGet();
        log.info("提交物流追踪请求: orderId={}", orderId);

        LogisticsTask task = LogisticsTask.builder()
                .taskId(IdGenerator.generateId("task"))
                .orderId(orderId)
                .status(LogisticsTask.TaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .priority("normal")
                .build();

        String pendingQueueKey = LogisticsTask.generatePendingQueueKey();
        redisQueueService.pushToQueue(pendingQueueKey, task);

        String taskKey = LogisticsTask.generateRedisKey(orderId);
        redisQueueService.setHashValue(taskKey, "task", task);
        redisQueueService.setHashValue(taskKey, "status", LogisticsTask.TaskStatus.PENDING.getCode());
        redisQueueService.setHashValue(taskKey, "createdAt", task.getCreatedAt().toString());

        Map<String, Object> response = new HashMap<>();
        response.put("orderId", orderId);
        response.put("status", "submitted");
        response.put("requestTime", LocalDateTime.now().toString());
        response.put("taskId", task.getTaskId());
        response.put("persisted", true);

        log.info("物流追踪任务已持久化: orderId={}, taskId={}", orderId, task.getTaskId());
        return CompletableFuture.completedFuture(response);
    }

    @Async
    public CompletableFuture<LogisticsTracking> executeTrackingAsync(String orderId) {
        workerExecutions.add("executeTrackingAsync_" + orderId);
        asyncTaskCount.incrementAndGet();
        log.info("后台Worker执行物流查询: orderId={}", orderId);

        String taskKey = LogisticsTask.generateRedisKey(orderId);
        Object taskObj = redisQueueService.getHashValue(taskKey, "task");
        LogisticsTask task = taskObj instanceof LogisticsTask ? (LogisticsTask) taskObj : null;

        if (task != null) {
            redisQueueService.setHashValue(taskKey, "status", LogisticsTask.TaskStatus.PROCESSING.getCode());
            task.setStatus(LogisticsTask.TaskStatus.PROCESSING);
            task.setExecutedAt(LocalDateTime.now());
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        LogisticsTracking tracking = null;
        try {
            tracking = simulateTrackingWithUpdate(orderId);

            if (task != null) {
                task.setStatus(LogisticsTask.TaskStatus.COMPLETED);
                redisQueueService.setHashValue(taskKey, "status", LogisticsTask.TaskStatus.COMPLETED.getCode());
                redisQueueService.setHashValue(taskKey, "completedAt", LocalDateTime.now().toString());
                redisQueueService.addToSet(LogisticsTask.generateCompletedSetKey(), orderId);
            }
        } catch (Exception e) {
            log.error("物流查询执行失败: orderId={}, error={}", orderId, e.getMessage());
            if (task != null && task.canRetry()) {
                task.incrementRetry();
                redisQueueService.setHashValue(taskKey, "status", LogisticsTask.TaskStatus.RETRYING.getCode());
                redisQueueService.setHashValue(taskKey, "retryCount", task.getRetryCount());
                redisQueueService.setHashValue(taskKey, "nextRetryAt", task.getNextRetryAt().toString());
                redisQueueService.pushToQueue(LogisticsTask.generateRetryQueueKey(), task);
            } else if (task != null) {
                task.setStatus(LogisticsTask.TaskStatus.FAILED);
                task.setErrorMessage(e.getMessage());
                redisQueueService.setHashValue(taskKey, "status", LogisticsTask.TaskStatus.FAILED.getCode());
                redisQueueService.setHashValue(taskKey, "errorMessage", e.getMessage());
                redisQueueService.addToSet(LogisticsTask.generateFailedSetKey(), orderId);
            }
        }

        log.info("后台Worker完成物流更新: orderId={}", orderId);
        return CompletableFuture.completedFuture(tracking);
    }

    @Scheduled(fixedRate = 10000)
    public void processPersistedTasks() {
        String pendingQueueKey = LogisticsTask.generatePendingQueueKey();
        long pendingCount = redisQueueService.getQueueSize(pendingQueueKey);

        if (pendingCount > 0) {
            log.info("检测到持久化待处理任务队列: count={}", pendingCount);
        }

        while (true) {
            Object taskObj = redisQueueService.popFromQueue(pendingQueueKey);
            if (taskObj == null) {
                break;
            }

            if (taskObj instanceof LogisticsTask task) {
                log.info("从持久化队列消费任务: orderId={}, taskId={}",
                        task.getOrderId(), task.getTaskId());

                String processingQueueKey = LogisticsTask.generateProcessingQueueKey();
                redisQueueService.pushToQueue(processingQueueKey, task);

                executeTrackingAsync(task.getOrderId());

                redisQueueService.popFromQueue(processingQueueKey);
            }
        }

        processRetryTasks();
    }

    private void processRetryTasks() {
        String retryQueueKey = LogisticsTask.generateRetryQueueKey();
        List<Object> retryTasks = redisQueueService.popBatchFromQueue(retryQueueKey, 10);

        for (Object taskObj : retryTasks) {
            if (taskObj instanceof LogisticsTask task) {
                if (task.getNextRetryAt() != null &&
                        task.getNextRetryAt().isBefore(LocalDateTime.now())) {
                    log.info("执行重试任务: orderId={}, retryCount={}",
                            task.getOrderId(), task.getRetryCount());
                    executeTrackingAsync(task.getOrderId());
                } else {
                    redisQueueService.pushToQueue(retryQueueKey, task);
                }
            }
        }
    }

    public Map<String, Object> getPersistedTaskInfo(String orderId) {
        Map<String, Object> info = new HashMap<>();
        String taskKey = LogisticsTask.generateRedisKey(orderId);

        Object taskObj = redisQueueService.getHashValue(taskKey, "task");
        if (taskObj instanceof LogisticsTask task) {
            info.put("taskId", task.getTaskId());
            info.put("orderId", task.getOrderId());
            info.put("status", task.getStatus() != null ? task.getStatus().getCode() : null);
            info.put("retryCount", task.getRetryCount());
            info.put("maxRetries", task.getMaxRetries());
            info.put("createdAt", task.getCreatedAt());
            info.put("executedAt", task.getExecutedAt());
            info.put("priority", task.getPriority());
        }

        Map<String, Object> hashValues = redisQueueService.getAllHashValues(taskKey);
        info.put("hashValues", hashValues);

        info.put("isInCompletedSet", redisQueueService.isInSet(
                LogisticsTask.generateCompletedSetKey(), orderId));
        info.put("isInFailedSet", redisQueueService.isInSet(
                LogisticsTask.generateFailedSetKey(), orderId));

        return info;
    }

    public Map<String, Object> getQueueStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("pendingQueueSize", redisQueueService.getQueueSize(
                LogisticsTask.generatePendingQueueKey()));
        stats.put("processingQueueSize", redisQueueService.getQueueSize(
                LogisticsTask.generateProcessingQueueKey()));
        stats.put("retryQueueSize", redisQueueService.getQueueSize(
                LogisticsTask.generateRetryQueueKey()));

        stats.put("completedCount", redisQueueService.getSetMembers(
                LogisticsTask.generateCompletedSetKey()).size());
        stats.put("failedCount", redisQueueService.getSetMembers(
                LogisticsTask.generateFailedSetKey()).size());

        stats.put("asyncTaskCount", asyncTaskCount.get());
        stats.put("workerExecutionCount", workerExecutions.size());

        return stats;
    }

    public void recoverFromPersistence() {
        log.info("开始从持久化存储恢复物流查询任务...");

        String processingQueueKey = LogisticsTask.generateProcessingQueueKey();
        List<Object> stuckTasks = redisQueueService.popBatchFromQueue(processingQueueKey, 100);

        for (Object taskObj : stuckTasks) {
            if (taskObj instanceof LogisticsTask task) {
                log.warn("恢复执行中任务: orderId={}, taskId={}",
                        task.getOrderId(), task.getTaskId());
                redisQueueService.pushToQueue(LogisticsTask.generatePendingQueueKey(), task);
            }
        }

        String retryQueueKey = LogisticsTask.generateRetryQueueKey();
        long retryCount = redisQueueService.getQueueSize(retryQueueKey);
        if (retryCount > 0) {
            log.info("恢复重试队列: count={}", retryCount);
        }

        String pendingQueueKey = LogisticsTask.generatePendingQueueKey();
        long pendingCount = redisQueueService.getQueueSize(pendingQueueKey);
        if (pendingCount > 0) {
            log.info("恢复待处理队列: count={}", pendingCount);
        }

        log.info("物流查询任务恢复完成: pending={}, retry={}", pendingCount, retryCount);
    }

    @Transactional
    public LogisticsTracking simulateTrackingWithUpdate(String orderId) {
        LogisticsTracking tracking = getTrackingByOrder(orderId);
        String currentStatus = tracking.getTrackingStatus();
        String nextStatus = getNextStatus(currentStatus);

        if (nextStatus != null) {
            String[] locations = {"仓库待发货", "分拣中心", "运输途中", "目的地城市", "已签收"};
            int index = switch (nextStatus) {
                case "pending" -> 0;
                case "in_transit" -> 2;
                case "arrived" -> 3;
                case "signed" -> 4;
                default -> 1;
            };

            tracking = updateTrackingStatusWithNotification(orderId, nextStatus, locations[index], null);

            if (TrackingStatus.ARRIVED.getCode().equals(nextStatus) ||
                TrackingStatus.SIGNED.getCode().equals(nextStatus)) {
                updateOrderStatusOnArrival(orderId, nextStatus);
            }
        }
        return tracking;
    }

    @Transactional
    public LogisticsTracking updateTrackingStatusWithNotification(
            String orderId, String status, String location, String description) {

        LogisticsTracking tracking = getTrackingByOrder(orderId);
        String oldStatus = tracking.getTrackingStatus();

        tracking.setTrackingStatus(status);
        tracking.setTrackingLocation(location);
        tracking.setTrackingTime(LocalDateTime.now());

        if (tracking.getTrackingRecords() == null) {
            tracking.setTrackingRecords(new ArrayList<>());
        }

        TrackingRecord record = TrackingRecord.builder()
            .status(status)
            .location(location)
            .description(description != null ? description : getStatusDescription(status))
            .time(LocalDateTime.now())
            .build();
        tracking.getTrackingRecords().add(record);

        trackingMapper.updateById(tracking);

        if (!oldStatus.equals(status)) {
            sendStatusChangeNotification(orderId, oldStatus, status, location);
            orderStatusUpdates.put(orderId, status);
            log.info("物流状态变更: orderId={}, {} -> {}", orderId, oldStatus, status);
        }

        return tracking;
    }

    private void sendStatusChangeNotification(String orderId, String oldStatus, String newStatus, String location) {
        String notification = String.format(
            "[物流通知] 订单%s物流状态变更: %s -> %s, 当前位置: %s",
            orderId, oldStatus, newStatus, location
        );
        statusNotifications.add(notification);
        log.info("发送物流状态通知: {}", notification);
    }

    private void updateOrderStatusOnArrival(String orderId, String logisticsStatus) {
        String message = String.format(
            "[订单更新] 订单%s因物流到达，更新订单状态", orderId
        );
        statusNotifications.add(message);
        log.info("物流到达，准备更新订单状态: orderId={}, logisticsStatus={}", orderId, logisticsStatus);
    }

    public List<String> getStatusNotifications() {
        return new ArrayList<>(statusNotifications);
    }

    public void clearStatusNotifications() {
        statusNotifications.clear();
    }

    public Map<String, String> getOrderStatusUpdates() {
        return new ConcurrentHashMap<>(orderStatusUpdates);
    }

    public int getAsyncTaskCount() {
        return asyncTaskCount.get();
    }

    public List<String> getWorkerExecutions() {
        return new ArrayList<>(workerExecutions);
    }

    public void resetAsyncMetrics() {
        asyncTaskCount.set(0);
        workerExecutions.clear();
        orderStatusUpdates.clear();
        statusNotifications.clear();
    }

    public boolean isTrackingRequestSubmitted(String orderId) {
        String taskKey = LogisticsTask.generateRedisKey(orderId);
        return redisQueueService.hasKey(taskKey) ||
                redisQueueService.isInSet(LogisticsTask.generateCompletedSetKey(), orderId) ||
                orderStatusUpdates.containsKey(orderId) ||
                workerExecutions.stream()
                    .anyMatch(e -> e.contains(orderId))
                    .findFirst()
                    .isPresent();
    }

    public Map<String, Object> getAsyncMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("asyncTaskCount", asyncTaskCount.get());
        metrics.put("workerExecutionCount", workerExecutions.size());
        metrics.put("statusNotificationCount", statusNotifications.size());
        metrics.put("orderStatusUpdateCount", orderStatusUpdates.size());
        return metrics;
    }

    public boolean hasArrivalNotificationSent(String orderId) {
        return statusNotifications.stream()
            .anyMatch(n -> n.contains(orderId) &&
                (n.contains("已到达") || n.contains("已签收") || n.contains("订单更新")));
    }

    public void clearAllPersistedData() {
        redisQueueService.clearAll();
        log.info("所有持久化物流查询数据已清空");
    }
}
