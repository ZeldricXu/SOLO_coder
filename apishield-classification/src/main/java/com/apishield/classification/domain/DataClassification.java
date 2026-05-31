package com.apishield.classification.domain;

import com.apishield.domain.entity.BaseEntity;
import com.apishield.domain.vo.SecurityLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataClassification extends BaseEntity {
    private String classificationId;
    private String dataSource;
    private String tableName;
    private String columnName;
    private String dataType;
    private String dataCategory;
    private SecurityLevel securityLevel;
    private String sensitivePattern;
    private double confidenceScore;
    private String policyId;
    private LocalDateTime scannedAt;
    private String scanJobId;
    private String status;
}
