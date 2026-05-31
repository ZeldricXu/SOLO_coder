package com.solocoder.platform.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResult {

    private String batchId;
    private int totalOperations;
    private int successCount;
    private int failedCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long durationMs;
    private List<OperationResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResult {
        private BatchOperationRequest.OperationType type;
        private String key;
        private boolean success;
        private String errorMessage;
        private byte[] data;
        private Map<String, String> metadata;
        private long size;
    }
}
