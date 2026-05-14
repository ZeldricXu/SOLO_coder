package com.stockmgmt.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InboundResponse {

    private String stockId;
    private String recordId;
    private String batchId;
    private Integer currentQuantity;
    private Integer availableQuantity;
    private String taskId;
    private Boolean async;
}
