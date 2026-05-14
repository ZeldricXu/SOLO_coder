package com.crm.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryTask {
    
    private String taskId;
    private String customerId;
    private String customerValue;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastRetryAt;
    private String status;
    private String errorMessage;
}
