package com.taskplatform.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class OperationResult {

    private String batchId;
    private List<ItemResult> results;

    @Data
    public static class ItemResult {
        private String id;
        private String action;
        private boolean success;
        private String message;
        private Object data;
    }
}
