package com.datamasker.interfaces.dto.classification;

import lombok.Data;

import java.util.Map;

@Data
public class ScanRequest {

    private String dataSource;

    private Map<String, String> fields;
}
