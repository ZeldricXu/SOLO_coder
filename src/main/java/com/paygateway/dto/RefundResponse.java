package com.paygateway.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RefundResponse {
    
    private String gatewayRefundId;
    private String channelRefundNo;
    private String status;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
