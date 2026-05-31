package com.edgescheduler.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchId;
    private List<OperationResult> results;
    private int successCount;
    private int failedCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private String id;
        private String action;
        private boolean success;
        private int code;
        private String message;
        private Object data;
    }
}
