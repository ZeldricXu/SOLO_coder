package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    private String orderId;

    private String carrier;

    private String trackingNumber;

    private TaskStatus status;

    private int retryCount;

    private int maxRetries;

    private LocalDateTime createdAt;

    private LocalDateTime executedAt;

    private LocalDateTime nextRetryAt;

    private String errorMessage;

    private Map<String, Object> metadata;

    private String priority;

    public enum TaskStatus {
        PENDING("pending", "待执行"),
        PROCESSING("processing", "执行中"),
        COMPLETED("completed", "已完成"),
        FAILED("failed", "执行失败"),
        RETRYING("retrying", "重试中"),
        CANCELLED("cancelled", "已取消");

        private final String code;
        private final String description;

        TaskStatus(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    public static String generateRedisKey(String orderId) {
        return "logistics:task:" + orderId;
    }

    public static String generatePendingQueueKey() {
        return "logistics:queue:pending";
    }

    public static String generateProcessingQueueKey() {
        return "logistics:queue:processing";
    }

    public static String generateRetryQueueKey() {
        return "logistics:queue:retry";
    }

    public static String generateCompletedSetKey() {
        return "logistics:set:completed";
    }

    public static String generateFailedSetKey() {
        return "logistics:set:failed";
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetry() {
        this.retryCount++;
        this.status = TaskStatus.RETRYING;
        this.nextRetryAt = LocalDateTime.now().plusMinutes(1L * this.retryCount);
    }

    public boolean isExpired() {
        if (createdAt == null) {
            return false;
        }
        return createdAt.plusHours(24).isBefore(LocalDateTime.now());
    }
}
