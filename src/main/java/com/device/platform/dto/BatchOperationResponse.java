package com.device.platform.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchOperationResponse {
    private String batchId;
    private List<OperationResult> results;

    @Data
    public static class OperationResult {
        private String id;
        private String action;
        private int code;
        private String message;
        private Object data;
    }
}
