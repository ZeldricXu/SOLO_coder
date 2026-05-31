package com.datamasker.interfaces.dto.privacy;

import lombok.Data;

import java.util.List;

@Data
public class BatchAddNoiseResponse {

    private String requestId;

    private int totalItems;

    private int successCount;

    private List<BatchResultItem> results;

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
