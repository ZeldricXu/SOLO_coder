package com.eventticket.dto;

import lombok.Data;

@Data
public class StatisticsResponse {
    private String statMonth;
    private Integer eventCount;
    private Integer ticketCount;
    private Long totalAmount;
    private Integer admissionCount;
    private Long refundCount;
    private Integer availableSeats;
}
