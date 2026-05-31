package com.observability.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchOperationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchId;

    private List<OperationResult> results;

    @Data
    public static class OperationResult implements Serializable {
        private String id;
        private String action;
        private boolean success;
        private String message;
    }
}
