package com.edgescheduler.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchResultVO {
    private String batchId;
    private List<OperationResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResult {
        private String id;
        private String action;
        private boolean success;
        private String message;
    }
}
