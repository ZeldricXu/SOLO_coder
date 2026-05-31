package com.datamasker.interfaces.dto.classification;

import lombok.Data;

import java.util.List;

@Data
public class ScanResponse {

    private String dataSource;

    private int totalFields;

    private int classifiedFields;

    private int sensitiveFields;

    private List<FieldClassification> results;

    @Data
    public static class FieldClassification {

        private String fieldName;

        private String category;

        private String level;

        private double confidence;
    }
}
