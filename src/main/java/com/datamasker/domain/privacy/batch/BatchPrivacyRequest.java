package com.datamasker.domain.privacy.batch;

import lombok.Data;

import java.util.List;

@Data
public class BatchPrivacyRequest {

    private String requestId;

    private List<BatchItem> items;

    @Data
    public static class BatchItem {

        private String queryId;

        private double value;

        private double sensitivity;

        private String mechanism;

        private Double epsilon;

        private Double delta;
    }
}
