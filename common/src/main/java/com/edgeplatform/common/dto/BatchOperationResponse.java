package com.edgeplatform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String batchId;
    private List<OperationResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResult implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String action;
        private boolean success;
        private String message;
    }
}
