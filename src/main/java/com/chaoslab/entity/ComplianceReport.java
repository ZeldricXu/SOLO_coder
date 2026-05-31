package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("compliance_report")
public class ComplianceReport extends BaseEntity {

    private String reportId;
    private String name;
    private String type;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Map<String, Object> filters;
    private Map<String, Object> summary;
    private Map<String, Object> details;
    private String generatedBy;
    private LocalDateTime generatedAt;
    private String status;
    private String filePath;
}
