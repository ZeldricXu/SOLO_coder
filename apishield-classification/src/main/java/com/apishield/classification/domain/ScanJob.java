package com.apishield.classification.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScanJob extends BaseEntity {
    private String jobId;
    private String jobName;
    private String dataSource;
    private List<String> tables;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int totalScanned;
    private int sensitiveFound;
    private String errorMessage;

    public ScanJob() {
        this.tables = new ArrayList<>();
    }
}
