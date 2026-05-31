package com.apishield.audit.api;

import com.apishield.audit.domain.model.AuditLog;

public interface AuditLogCreator {
    AuditLog createLog(String operation, String operatorId, String operatorName,
                       String resourceType, String resourceId, String requestParams,
                       String responseResult, String status, String ipAddress, String userAgent);
}
