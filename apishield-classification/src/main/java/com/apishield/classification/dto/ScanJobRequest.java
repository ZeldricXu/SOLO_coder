package com.apishield.classification.dto;

import lombok.Data;
import java.util.List;

@Data
public class ScanJobRequest {
    private String jobName;
    private String dataSource;
    private List<String> tables;
    private String scannerType;
}
