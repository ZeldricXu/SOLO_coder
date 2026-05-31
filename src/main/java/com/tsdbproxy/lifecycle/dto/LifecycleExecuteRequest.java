package com.tsdbproxy.lifecycle.dto;

import lombok.Data;

@Data
public class LifecycleExecuteRequest {

    private Long policyId;
    private String operationType;
}
