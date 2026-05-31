package com.parking.platform.notification.entity;

import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Notification extends BaseEntity {

    private String type;
    private String channel;
    private String recipient;
    private String subject;
    private String content;
    private String status;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant failedAt;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;
    private List<DeliveryAttempt> deliveryAttempts;
    private Map<String, Object> metadata;

    public Notification() {
        super();
        this.status = "PENDING";
        this.retryCount = 0;
        this.maxRetries = 3;
        this.deliveryAttempts = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    @Override
    protected String getIdPrefix() {
        return "notif";
    }

    public void recordAttempt(boolean success, String message) {
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.setAttemptNumber(deliveryAttempts.size() + 1);
        attempt.setSuccess(success);
        attempt.setMessage(message);
        attempt.setTimestamp(Instant.now());
        this.deliveryAttempts.add(attempt);
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("type", type);
        map.put("channel", channel);
        map.put("recipient", recipient);
        map.put("subject", subject);
        map.put("status", status);
        map.put("sentAt", sentAt);
        map.put("deliveredAt", deliveredAt);
        map.put("failedAt", failedAt);
        map.put("errorMessage", errorMessage);
        map.put("retryCount", retryCount);
        map.put("maxRetries", maxRetries);
        map.put("deliveryAttempts", deliveryAttempts);
        map.put("metadata", metadata);
        return map;
    }

    public static class DeliveryAttempt {
        private Integer attemptNumber;
        private boolean success;
        private String message;
        private Instant timestamp;

        public Integer getAttemptNumber() { return attemptNumber; }
        public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public Instant getFailedAt() { return failedAt; }
    public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public List<DeliveryAttempt> getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(List<DeliveryAttempt> deliveryAttempts) { this.deliveryAttempts = deliveryAttempts; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
