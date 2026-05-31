package com.tsdbproxy.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResponse {

    private String batchId;
    private List<OperationResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResult {
        private String id;
        private String action;
        private String status;
        private String message;
    }
}
