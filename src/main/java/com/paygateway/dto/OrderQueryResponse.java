package com.paygateway.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderQueryResponse {
    
    private String gatewayOrderId;
    private String merchantOrderNo;
    private String channelOrderNo;
    private String status;
    private BigDecimal amount;
    private String currency;
    private String channel;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
