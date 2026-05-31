package com.datamasker.domain.privacy.batch;

import lombok.Data;

import java.util.List;

@Data
public class BatchPrivacyResult {

    private String requestId;

    private List<BatchResultItem> results;

    private int totalItems;

    private int successCount;

    private int failureCount;

    private long totalLatencyMs;

    @Data
    public static class BatchResultItem {

        private String queryId;

        private double originalValue;

        private double noiseAdded;

        private double noisyValue;

        private boolean success;

        private String errorMessage;
    }
}
