package com.ratelimiter.service.circuit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationQueueItem implements Serializable {
    
    private String id;
    private String callbackUrl;
    private CircuitStateChangeEvent event;
    private int retryCount;
    private int maxRetryCount;
    private Instant nextRetryTime;
    private Instant createdTime;
    private long retryDelayMs;
    private boolean completed;
    private String lastError;
    
    public static NotificationQueueItem create(String callbackUrl, CircuitStateChangeEvent event,
                                                 int maxRetryCount, long initialDelayMs) {
        String id = "notify_" + System.currentTimeMillis() + "_" + 
                event.getCircuitId().hashCode();
        
        return NotificationQueueItem.builder()
                .id(id)
                .callbackUrl(callbackUrl)
                .event(event)
                .retryCount(0)
                .maxRetryCount(maxRetryCount)
                .nextRetryTime(Instant.now())
                .createdTime(Instant.now())
                .retryDelayMs(initialDelayMs)
                .completed(false)
                .lastError(null)
                .build();
    }
    
    public void calculateNextRetryTime() {
        retryCount++;
        long delayMs = retryDelayMs * (long) Math.pow(2, retryCount - 1);
        nextRetryTime = Instant.now().plusMillis(delayMs);
    }
    
    public boolean canRetry() {
        return !completed && retryCount < maxRetryCount;
    }
    
    public boolean isReadyForRetry() {
        return !completed && Instant.now().isAfter(nextRetryTime);
    }
}