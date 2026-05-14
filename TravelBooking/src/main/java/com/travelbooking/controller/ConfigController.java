package com.travelbooking.controller;

import com.travelbooking.config.BookingLockConfig;
import com.travelbooking.config.ItineraryReminderConfig;
import com.travelbooking.config.RouteTypeConfig;
import com.travelbooking.config.SettlementConfig;
import com.travelbooking.dto.ApiResponse;
import com.travelbooking.service.RedisSettlementQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final BookingLockConfig bookingLockConfig;
    private final ItineraryReminderConfig reminderConfig;
    private final RouteTypeConfig routeTypeConfig;
    private final SettlementConfig settlementConfig;
    private final RedisSettlementQueueService queueService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllConfigs() {
        Map<String, Object> configs = new LinkedHashMap<>();
        
        configs.put("bookingLock", getBookingLockConfig());
        configs.put("itineraryReminder", getItineraryReminderConfig());
        configs.put("routeTypes", getRouteTypeConfig());
        configs.put("settlement", getSettlementConfig());
        
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/booking-lock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBookingLockConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("emergencyTimeoutSeconds", bookingLockConfig.getEmergencyTimeoutSeconds());
        config.put("normalTimeoutSeconds", bookingLockConfig.getNormalTimeoutSeconds());
        config.put("defaultTimeUnit", bookingLockConfig.getDefaultTimeUnit().name());
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/itinerary-reminder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getItineraryReminderConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("longTripMinDays", reminderConfig.getLongTripMinDays());
        config.put("longTripReminderDays", reminderConfig.getLongTripReminderDays());
        config.put("shortTripMinDays", reminderConfig.getShortTripMinDays());
        config.put("shortTripReminderDays", reminderConfig.getShortTripReminderDays());
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/route-types")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRouteTypeConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("totalTypes", routeTypeConfig.size());
        config.put("enabledTypes", routeTypeConfig.getAllEnabledTypes().stream().map(t -> {
            Map<String, Object> typeMap = new LinkedHashMap<>();
            typeMap.put("code", t.getCode());
            typeMap.put("name", t.getName());
            typeMap.put("description", t.getDescription());
            typeMap.put("enabled", t.isEnabled());
            typeMap.put("order", t.getOrder());
            typeMap.put("defaultDuration", t.getDefaultDuration());
            typeMap.put("priceFactor", t.getPriceFactor());
            return typeMap;
        }).toList());
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/settlement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSettlementConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("redisQueueName", settlementConfig.getRedisQueueName());
        config.put("retryQueueName", settlementConfig.getRetryQueueName());
        config.put("deadLetterQueueName", settlementConfig.getDeadLetterQueueName());
        config.put("maxRetryAttempts", settlementConfig.getMaxRetryAttempts());
        config.put("retryDelayMs", settlementConfig.getRetryDelayMs());
        config.put("workerPoolSize", settlementConfig.getWorkerPoolSize());
        config.put("persistenceEnabled", settlementConfig.isPersistenceEnabled());
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/queue-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQueueStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("queue", queueService.getQueueStats());
        stats.put("config", getSettlementConfig().getBody().getData());
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/dead-letter/retry/{taskId}")
    public ResponseEntity<ApiResponse<Boolean>> retryDeadLetterTask(@PathVariable String taskId) {
        boolean success = queueService.retryDeadLetterTask(taskId);
        if (success) {
            return ResponseEntity.ok(ApiResponse.success(true, "任务已重新入队"));
        } else {
            return ResponseEntity.ok(ApiResponse.error(404, "未找到任务或重试失败"));
        }
    }

    @GetMapping("/dead-letter")
    public ResponseEntity<ApiResponse<Object>> getDeadLetterTasks(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(ApiResponse.success(queueService.getDeadLetterTasks(limit)));
    }
}
