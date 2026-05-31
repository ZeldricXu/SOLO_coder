package com.metricplatform.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchId;
    private List<OperationResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResult implements Serializable {
        private String id;
        private String action;
        private boolean success;
        private String message;
        private Object data;
    }
}
