package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrder implements Serializable {
    private String orderId;
    private String supplierId;
    private String orderType;
    private List<OrderItem> orderItems;
    private BigDecimal orderAmount;
    private String orderStatus;
    private String approver;
    private String rejectReason;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime receivedAt;
}
