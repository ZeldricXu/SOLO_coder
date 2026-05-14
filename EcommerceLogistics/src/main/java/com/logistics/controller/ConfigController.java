package com.logistics.controller;

import com.logistics.config.CourierLockConfig;
import com.logistics.config.TrackConfig;
import com.logistics.config.NotificationConfig;
import com.logistics.service.CourierLockService;
import com.logistics.service.TrackBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final CourierLockConfig courierLockConfig;
    private final TrackConfig trackConfig;
    private final NotificationConfig notificationConfig;
    private final CourierLockService courierLockService;
    private final TrackBatchService trackBatchService;

    @GetMapping("/lock-timeouts")
    public ResponseEntity<Map<String, Long>> getLockTimeouts() {
        return ResponseEntity.ok(courierLockConfig.getAllTimeouts());
    }

    @PutMapping("/lock-timeouts/{urgency}")
    public ResponseEntity<Map<String, Long>> updateLockTimeout(
            @PathVariable String urgency,
            @RequestParam long timeoutSeconds) {
        log.info("更新锁定超时: {} -> {}秒", urgency, timeoutSeconds);
        courierLockService.updateTimeoutConfig(urgency, timeoutSeconds);
        return ResponseEntity.ok(courierLockConfig.getAllTimeouts());
    }

    @GetMapping("/track")
    public ResponseEntity<Map<String, Object>> getTrackConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("highFrequencyThreshold", trackConfig.getHighFrequencyThreshold());
        config.put("highFrequencyWindowSeconds", trackConfig.getHighFrequencyWindowSeconds());
        config.put("batchFlushThreshold", trackConfig.getBatchFlushThreshold());
        config.put("batchFlushIntervalMs", trackConfig.getBatchFlushIntervalMs());
        return ResponseEntity.ok(config);
    }

    @GetMapping("/notification")
    public ResponseEntity<Map<String, Object>> getNotificationConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("redisQueueName", notificationConfig.getRedisQueueName());
        config.put("redisFailedQueueName", notificationConfig.getRedisFailedQueueName());
        config.put("maxRetryCount", notificationConfig.getMaxRetryCount());
        config.put("retryDelayMs", notificationConfig.getRetryDelayMs());
        config.put("failedCheckIntervalMs", notificationConfig.getFailedCheckIntervalMs());
        config.put("maxWorkers", notificationConfig.getMaxWorkers());
        return ResponseEntity.ok(config);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        
        Map<String, Object> trackStatus = new HashMap<>();
        trackStatus.put("highFrequencyThreshold", trackBatchService.getHighFrequencyThreshold());
        trackStatus.put("highFrequencyWindowSeconds", trackBatchService.getHighFrequencyWindowSeconds());
        trackStatus.put("batchFlushThreshold", trackBatchService.getBatchFlushThreshold());
        trackStatus.put("batchFlushIntervalMs", trackBatchService.getBatchFlushIntervalMs());
        status.put("track", trackStatus);
        
        Map<String, Object> lockStatus = new HashMap<>();
        lockStatus.put("timeouts", courierLockService.getAllTimeoutConfigs());
        status.put("lock", lockStatus);
        
        return ResponseEntity.ok(status);
    }
}
