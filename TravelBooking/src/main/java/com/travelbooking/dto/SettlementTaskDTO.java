package com.travelbooking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementTaskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String itineraryId;
    private String bookingId;
    private String touristId;
    private BigDecimal settlementAmount;
    private String paymentMethod;
    private int retryCount;
    private int maxRetries;
    private Instant createTime;
    private Instant nextRetryTime;
    private String lastError;
    private String status;

    public static SettlementTaskDTO create(String itineraryId, String bookingId, String touristId, BigDecimal amount) {
        return SettlementTaskDTO.builder()
                .taskId("settle_" + Instant.now().toEpochMilli() + "_" + bookingId)
                .itineraryId(itineraryId)
                .bookingId(bookingId)
                .touristId(touristId)
                .settlementAmount(amount)
                .paymentMethod("default")
                .retryCount(0)
                .maxRetries(3)
                .createTime(Instant.now())
                .status("pending")
                .build();
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public SettlementTaskDTO incrementRetry() {
        this.retryCount++;
        return this;
    }

    public SettlementTaskDTO setFailed(String error) {
        this.status = "failed";
        this.lastError = error;
        return this;
    }

    public SettlementTaskDTO setSuccess() {
        this.status = "success";
        return this;
    }
}
