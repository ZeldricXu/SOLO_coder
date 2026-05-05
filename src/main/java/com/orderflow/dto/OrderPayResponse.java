package com.orderflow.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OrderPayResponse {

    private String paymentId;
    private String orderId;
    private String status;
    private BigDecimal paymentAmount;
}
