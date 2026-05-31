package com.apishield.audit.domain.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AuditLog {
    private String id;
    private String logId;
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
    private long timestamp;
    private String previousHash;
    private String currentHash;
    private int blockHeight;
    private LocalDateTime createdAt;
}
