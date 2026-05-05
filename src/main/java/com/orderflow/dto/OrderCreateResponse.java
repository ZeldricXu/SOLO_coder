package com.orderflow.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderCreateResponse {

    private String orderId;
    private String orderNo;
    private String status;
}
