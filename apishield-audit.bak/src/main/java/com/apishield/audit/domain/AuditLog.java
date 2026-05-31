package com.apishield.audit.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AuditLog extends BaseEntity {
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
}
