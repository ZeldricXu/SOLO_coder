package com.chaoslab.modules.audit.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ComplianceReportRequest {

    @NotBlank(message = "报告名称不能为空")
    private String name;

    @NotBlank(message = "报告类型不能为空")
    private String type;

    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Map<String, Object> filters;
    private String generatedBy;
}
