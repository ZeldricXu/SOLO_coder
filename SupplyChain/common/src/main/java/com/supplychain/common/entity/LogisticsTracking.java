package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsTracking implements Serializable {
    private String trackingId;
    private String orderId;
    private String trackingStatus;
    private String trackingLocation;
    private LocalDateTime trackingTime;
    private String carrier;
    private String trackingNumber;
    private List<TrackingRecord> trackingRecords;
}
