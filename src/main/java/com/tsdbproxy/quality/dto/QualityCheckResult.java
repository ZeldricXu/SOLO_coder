package com.tsdbproxy.quality.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityCheckResult {

    private Long ruleId;
    private String ruleName;
    private String status;
    private String actualValue;
    private String expectedValue;
    private String errorMessage;
    private Long abnormalDataCount;
    private LocalDateTime checkTime;
}
