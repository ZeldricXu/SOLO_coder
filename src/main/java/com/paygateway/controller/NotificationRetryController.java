package com.paygateway.controller;

import com.paygateway.dto.ApiResponse;
import com.paygateway.service.AsyncNotificationService;
import com.paygateway.service.NotificationQueueItem;
import com.paygateway.service.RedisNotificationQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/notification")
@RequiredArgsConstructor
public class NotificationRetryController {
    
    private final RedisNotificationQueueService redisNotificationQueueService;
    private final AsyncNotificationService asyncNotificationService;
    
    @GetMapping("/pending")
    public ApiResponse<List<NotificationQueueItem>> getPendingRetries() {
        log.info("查询待处理的通知重试任务");
        List<NotificationQueueItem> retries = redisNotificationQueueService.getPendingNotifications();
        return ApiResponse.success(retries);
    }
    
    @GetMapping("/failed")
    public ApiResponse<List<NotificationQueueItem>> getFailedRetries() {
        log.info("查询失败的通知重试任务");
        List<NotificationQueueItem> retries = redisNotificationQueueService.getFailedNotifications();
        return ApiResponse.success(retries);
    }
    
    @GetMapping("/{retryId}")
    public ApiResponse<NotificationQueueItem> getByRetryId(@PathVariable String retryId) {
        log.info("查询通知重试任务详情：retryId={}", retryId);
        NotificationQueueItem item = redisNotificationQueueService.getByRetryId(retryId);
        if (item == null) {
            return ApiResponse.error(404, "重试记录不存在");
        }
        return ApiResponse.success(item);
    }
    
    @PostMapping("/retry/{retryId}")
    public ApiResponse<Map<String, Object>> manualRetry(@PathVariable String retryId) {
        log.info("手动触发通知重试：retryId={}", retryId);
        
        try {
            NotificationQueueItem item = redisNotificationQueueService.getByRetryId(retryId);
            if (item == null) {
                return ApiResponse.error(404, "重试记录不存在");
            }
            
            boolean success = asyncNotificationService.executeNotification(item);
            
            Map<String, Object> result = new HashMap<>();
            result.put("retryId", retryId);
            result.put("success", success);
            result.put("message", success ? "重试成功" : "重试失败，已加入重试队列");
            
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("手动重试异常：retryId={}", retryId, e);
            return ApiResponse.error(500, "重试失败：" + e.getMessage());
        }
    }
    
    @PostMapping("/reset/{retryId}")
    public ApiResponse<Map<String, Object>> resetRetry(@PathVariable String retryId) {
        log.info("重置通知重试：retryId={}", retryId);
        
        try {
            boolean success = redisNotificationQueueService.manualRetry(retryId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("retryId", retryId);
            result.put("success", success);
            result.put("message", "已重置并重试队列");
            
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("重置重试异常：retryId={}", retryId, e);
            return ApiResponse.error(500, "重置失败：" + e.getMessage());
        }
    }
    
    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        log.info("查询通知重试统计");
        
        long pendingCount = redisNotificationQueueService.getPendingCount();
        long failedCount = redisNotificationQueueService.getFailedCount();
        
        List<NotificationQueueItem> pending = redisNotificationQueueService.getPendingNotifications();
        List<NotificationQueueItem> failed = redisNotificationQueueService.getFailedNotifications();
        
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("pendingCount", pendingCount);
        statistics.put("failedCount", failedCount);
        statistics.put("pendingRetries", pending);
        statistics.put("failedRetries", failed);
        
        return ApiResponse.success(statistics);
    }
    
    @PostMapping("/retry-all-failed")
    public ApiResponse<Map<String, Object>> retryAllFailed() {
        log.info("手动重试所有失败的通知");
        
        List<NotificationQueueItem> failedItems = redisNotificationQueueService.getFailedNotifications();
        
        int successCount = 0;
        int failCount = 0;
        
        for (NotificationQueueItem item : failedItems) {
            try {
                redisNotificationQueueService.manualRetry(item.getRetryId());
                successCount++;
            } catch (Exception e) {
                log.error("重试失败通知异常：retryId={}", item.getRetryId(), e);
                failCount++;
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", failedItems.size());
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("message", "已处理 " + failedItems.size() + " 个失败通知");
        
        return ApiResponse.success(result);
    }
}
