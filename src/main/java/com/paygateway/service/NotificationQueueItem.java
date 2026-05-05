package com.paygateway.service;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class NotificationQueueItem implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String retryId;
    private String orderId;
    private String merchantId;
    private String merchantOrderNo;
    private String channelOrderNo;
    private String notifyUrl;
    private String notifyContent;
    private BigDecimal amount;
    private String status;
    private String channel;
    private LocalDateTime paidAt;
    
    private Integer retryCount;
    private Integer maxRetryCount;
    private String lastErrorMsg;
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastNotifyAt;
    private LocalDateTime createdAt;
}
