package com.meshcontrol.audit.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditQueryRequest {

    private String resourceType;
    private String resourceId;
    private String operator;
    private String action;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
