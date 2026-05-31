package com.datamasker.domain.classification.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ScanResult {

    private String dataSource;

    private int totalFields;

    private int classifiedFields;

    private int sensitiveFields;

    private List<DataField> results;

    private LocalDateTime scannedAt;
}
