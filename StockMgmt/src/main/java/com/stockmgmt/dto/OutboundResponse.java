package com.stockmgmt.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutboundResponse {

    private String stockId;
    private String recordId;
    private String lockId;
    private Integer currentQuantity;
    private Integer availableQuantity;
    private Integer lockedQuantity;
    private String taskId;
    private Boolean async;
}
