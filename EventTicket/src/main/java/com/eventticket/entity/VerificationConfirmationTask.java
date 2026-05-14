package com.eventticket.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationConfirmationTask implements Serializable {

    private String taskId;
    private String ticketId;
    private String eventId;
    private String seatId;
    private String operator;
    private LocalDateTime verifyTime;
    private int retryCount;
    private int maxRetries;
    private int retryDelaySeconds;
    private int backoffMultiplier;
    private LocalDateTime nextRetryTime;
    private String status;

    public static VerificationConfirmationTask create(
            String ticketId, 
            String eventId, 
            String seatId, 
            String operator,
            int maxRetries,
            int retryDelaySeconds,
            int backoffMultiplier) {
        return VerificationConfirmationTask.builder()
                .taskId("task_" + UUID.randomUUID().toString().substring(0, 12).toUpperCase())
                .ticketId(ticketId)
                .eventId(eventId)
                .seatId(seatId)
                .operator(operator)
                .verifyTime(LocalDateTime.now())
                .retryCount(0)
                .maxRetries(maxRetries)
                .retryDelaySeconds(retryDelaySeconds)
                .backoffMultiplier(backoffMultiplier)
                .nextRetryTime(LocalDateTime.now().plusSeconds(retryDelaySeconds))
                .status("PENDING")
                .build();
    }

    public boolean shouldRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetry() {
        this.retryCount++;
        int delay = retryDelaySeconds * (int) Math.pow(backoffMultiplier, retryCount - 1);
        this.nextRetryTime = LocalDateTime.now().plusSeconds(delay);
    }

    public boolean isTimeToRetry() {
        return LocalDateTime.now().isAfter(nextRetryTime);
    }

    public void markSuccess() {
        this.status = "SUCCESS";
    }

    public void markFailed() {
        this.status = "FAILED";
    }
}
