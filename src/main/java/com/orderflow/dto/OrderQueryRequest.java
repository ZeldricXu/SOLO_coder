package com.orderflow.dto;

import lombok.Data;

@Data
public class OrderQueryRequest {

    private String userId;
    private String status;
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
