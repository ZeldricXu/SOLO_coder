package com.datastandard.modules.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformResponse {

    private String requestId;

    private String status;

    private int totalRecords;

    private int successCount;

    private int failedCount;

    private List<Map<String, Object>> transformedRecords;

    private List<TransformError> errors;

    private Instant startTime;

    private Instant endTime;

    private long durationMs;

    private Map<String, Object> metrics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransformError {
        private int recordIndex;
        private String field;
        private String originalValue;
        private String errorCode;
        private String errorMessage;
    }
}
