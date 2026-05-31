package com.apishield.audit.api.dto;

import lombok.Data;

@Data
public class CreateAuditLogRequest {
    private String operation;
    private String operatorId;
    private String operatorName;
    private String resourceType;
    private String resourceId;
    private String requestParams;
    private String responseResult;
    private String status;
    private String ipAddress;
    private String userAgent;
}
