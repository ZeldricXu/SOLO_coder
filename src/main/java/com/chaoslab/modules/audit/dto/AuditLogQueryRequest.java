package com.chaoslab.modules.audit.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class AuditLogQueryRequest {

    private String actor;
    private String action;
    private String resourceType;
    private String resourceId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<String> complianceTags;
}
