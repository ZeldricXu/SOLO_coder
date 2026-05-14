package com.stockmgmt.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LockResponse {

    private String lockId;
    private String stockId;
    private Integer lockedQuantity;
    private Integer availableQuantity;
    private Integer timeoutSeconds;
    private String urgencyLevel;
}
